package com.runout.engine.vision

import com.runout.engine.math.Vec2
import com.runout.engine.model.TableSpec
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Immutable 3D vector in SI metres.
 *
 * In table coordinates `x` and `y` follow the table convention (origin at the head-left cushion nose)
 * and `z` is the height above the cloth.
 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * Pinhole camera intrinsics in pixels (square pixels, no skew or distortion).
 */
data class CameraIntrinsicsSpec(
    val focalLengthPx: Double,
    val principalPointPx: Vec2,
    val imageWidthPx: Int = 1920,
    val imageHeightPx: Int = 1080,
)

/**
 * Planar projective mapping between image pixels and table-cloth coordinates in metres.
 *
 * Both matrices are row-major 3x3 homogeneous transforms:
 * - [matrix] maps image `(u, v, 1)` to table `(X, Y, 1)`.
 * - [inverseMatrix] maps table `(X, Y, 1)` to image `(u, v, 1)`.
 */
class PerspectiveHomography(
    val matrix: DoubleArray,
    val inverseMatrix: DoubleArray,
) {
    init {
        require(matrix.size == 9 && inverseMatrix.size == 9) {
            "Homography matrices must have exactly 9 elements."
        }
    }

    /** Projects an image point onto the cloth plane (`z = 0`) in table metres. */
    fun imageToTableCloth(imagePoint: Vec2): Vec2 = applyProjective(matrix, imagePoint)

    /** Projects a cloth-plane table point (`z = 0`) into image pixels. */
    fun tableClothToImage(tablePoint: Vec2): Vec2 = applyProjective(inverseMatrix, tablePoint)

    /**
     * Converts the image position of a ball's visual centre (which lies at height [ballRadiusMeters]
     * above the cloth) to the ball-centre position in table metres.
     *
     * The cloth-plane projection of an elevated point is displaced away from the camera; this
     * intersects the camera ray with the plane `z = ballRadiusMeters` to undo that displacement.
     */
    fun imageToBallCenterMeters(
        imagePoint: Vec2,
        ballRadiusMeters: Double,
        cameraPositionMeters: Vec3,
    ): Vec2 {
        require(ballRadiusMeters >= 0.0) { "ballRadiusMeters must be non-negative." }
        require(cameraPositionMeters.z > ballRadiusMeters) {
            "Camera must be above the ball centre plane."
        }
        val clothPoint = imageToTableCloth(imagePoint)
        val factor = ballRadiusMeters / cameraPositionMeters.z
        return Vec2(
            clothPoint.x - factor * (clothPoint.x - cameraPositionMeters.x),
            clothPoint.y - factor * (clothPoint.y - cameraPositionMeters.y),
        )
    }

    /**
     * Inverse of [imageToBallCenterMeters]: the image position of the 3D ball centre
     * `(x, y, ballRadiusMeters)`.
     */
    fun ballCenterMetersToImage(
        ballCenterMeters: Vec2,
        ballRadiusMeters: Double,
        cameraPositionMeters: Vec3,
    ): Vec2 {
        require(cameraPositionMeters.z > ballRadiusMeters) {
            "Camera must be above the ball centre plane."
        }
        val scale = cameraPositionMeters.z / (cameraPositionMeters.z - ballRadiusMeters)
        val clothPoint = Vec2(
            cameraPositionMeters.x + scale * (ballCenterMeters.x - cameraPositionMeters.x),
            cameraPositionMeters.y + scale * (ballCenterMeters.y - cameraPositionMeters.y),
        )
        return tableClothToImage(clothPoint)
    }

    /**
     * Estimates the camera position in table coordinates (metres) by decomposing the table-to-image
     * homography as `K * [r1 r2 t]` for the given [intrinsics].
     *
     * Falls back to a typical standing position at the head end if the decomposition is degenerate.
     */
    fun estimateCameraPositionMeters(table: TableSpec, intrinsics: CameraIntrinsicsSpec): Vec3 {
        val fallback = Vec3(-0.50, table.widthMeters * 0.5, 1.65)
        val fx = intrinsics.focalLengthPx
        if (abs(fx) < 1e-9) return fallback
        val cx = intrinsics.principalPointPx.x
        val cy = intrinsics.principalPointPx.y

        fun column(j: Int): DoubleArray {
            val hu = inverseMatrix[j]
            val hv = inverseMatrix[3 + j]
            val hw = inverseMatrix[6 + j]
            return doubleArrayOf((hu - cx * hw) / fx, (hv - cy * hw) / fx, hw)
        }

        val b1 = column(0)
        val b2 = column(1)
        val b3 = column(2)
        val norm1 = norm(b1)
        val norm2 = norm(b2)
        if (norm1 < 1e-12 || norm2 < 1e-12) return fallback

        var scale = 1.0 / sqrt(norm1 * norm2)
        // The table origin must lie in front of the camera.
        if (b3[2] < 0.0) scale = -scale

        val r1 = doubleArrayOf(scale * b1[0], scale * b1[1], scale * b1[2])
        val r2 = doubleArrayOf(scale * b2[0], scale * b2[1], scale * b2[2])
        val r3 = cross(r1, r2)
        val t = doubleArrayOf(scale * b3[0], scale * b3[1], scale * b3[2])

        // C = -R^T t, with R = [r1 r2 r3] (columns).
        val cxm = -dot(r1, t)
        val cym = -dot(r2, t)
        val czm = abs(-dot(r3, t))
        if (!cxm.isFinite() || !cym.isFinite() || !czm.isFinite() || czm <= 0.3) return fallback
        return Vec3(cxm, cym, czm)
    }

    companion object {
        /**
         * Solves the 8-DOF homography mapping four [sourcePoints] to four [targetPoints] (direct linear
         * transform with `h22 = 1`) using Gaussian elimination with partial pivoting.
         *
         * @throws IllegalArgumentException if the point configuration is degenerate (e.g. collinear points).
         */
        fun fromPointCorrespondences(
            sourcePoints: List<Vec2>,
            targetPoints: List<Vec2>,
        ): PerspectiveHomography {
            require(sourcePoints.size == 4 && targetPoints.size == 4) {
                "Exactly four source and four target points are required."
            }
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val x = sourcePoints[i].x
                val y = sourcePoints[i].y
                val px = targetPoints[i].x
                val py = targetPoints[i].y
                val r0 = 2 * i
                val r1 = r0 + 1
                a[r0][0] = x
                a[r0][1] = y
                a[r0][2] = 1.0
                a[r0][6] = -px * x
                a[r0][7] = -px * y
                b[r0] = px
                a[r1][3] = x
                a[r1][4] = y
                a[r1][5] = 1.0
                a[r1][6] = -py * x
                a[r1][7] = -py * y
                b[r1] = py
            }
            val h = solveLinearSystem(a, b)
            require(h != null) { "Point correspondences are degenerate; homography is not solvable." }
            val forward = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
            val inverse = invert3x3(forward)
            require(inverse != null) { "Homography is singular." }
            return PerspectiveHomography(forward, inverse)
        }

        /**
         * Builds the image-to-table homography from the four table corners in the image, ordered
         * `[headLeftPx, footLeftPx, footRightPx, headRightPx]`.
         */
        fun fromTableQuad(imageCornersPx: List<Vec2>, table: TableSpec): PerspectiveHomography {
            require(imageCornersPx.size == 4) { "Exactly four table corners are required." }
            return fromPointCorrespondences(
                sourcePoints = imageCornersPx,
                targetPoints = listOf(
                    Vec2(0.0, 0.0),
                    Vec2(table.lengthMeters, 0.0),
                    Vec2(table.lengthMeters, table.widthMeters),
                    Vec2(0.0, table.widthMeters),
                ),
            )
        }

        private fun applyProjective(m: DoubleArray, p: Vec2): Vec2 {
            val xh = m[0] * p.x + m[1] * p.y + m[2]
            val yh = m[3] * p.x + m[4] * p.y + m[5]
            val w = m[6] * p.x + m[7] * p.y + m[8]
            return Vec2(xh / w, yh / w)
        }

        /** Gaussian elimination with partial pivoting; returns null when the system is singular. */
        private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { r -> a[r].copyOf() }
            val rhs = b.copyOf()
            for (col in 0 until n) {
                var pivotRow = col
                var pivotAbs = abs(m[col][col])
                for (r in col + 1 until n) {
                    val v = abs(m[r][col])
                    if (v > pivotAbs) {
                        pivotAbs = v
                        pivotRow = r
                    }
                }
                if (pivotAbs < 1e-12) return null
                if (pivotRow != col) {
                    val tmpRow = m[col]
                    m[col] = m[pivotRow]
                    m[pivotRow] = tmpRow
                    val tmpB = rhs[col]
                    rhs[col] = rhs[pivotRow]
                    rhs[pivotRow] = tmpB
                }
                for (r in col + 1 until n) {
                    val factor = m[r][col] / m[col][col]
                    if (factor == 0.0) continue
                    for (c in col until n) {
                        m[r][c] -= factor * m[col][c]
                    }
                    rhs[r] -= factor * rhs[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var sum = rhs[r]
                for (c in r + 1 until n) {
                    sum -= m[r][c] * x[c]
                }
                x[r] = sum / m[r][r]
            }
            return if (x.all { it.isFinite() }) x else null
        }

        /** Inverts a row-major 3x3 matrix, normalised so that element `[8] == 1` where possible. */
        private fun invert3x3(m: DoubleArray): DoubleArray? {
            val a = m[0]
            val b = m[1]
            val c = m[2]
            val d = m[3]
            val e = m[4]
            val f = m[5]
            val g = m[6]
            val h = m[7]
            val i = m[8]
            val c00 = e * i - f * h
            val c01 = c * h - b * i
            val c02 = b * f - c * e
            val c10 = f * g - d * i
            val c11 = a * i - c * g
            val c12 = c * d - a * f
            val c20 = d * h - e * g
            val c21 = b * g - a * h
            val c22 = a * e - b * d
            val det = a * c00 + b * c10 + c * c20
            if (abs(det) < 1e-18 || !det.isFinite()) return null
            val inv = doubleArrayOf(
                c00 / det, c01 / det, c02 / det,
                c10 / det, c11 / det, c12 / det,
                c20 / det, c21 / det, c22 / det,
            )
            if (abs(inv[8]) > 1e-12) {
                val s = inv[8]
                for (k in inv.indices) inv[k] = inv[k] / s
            }
            return inv
        }

        private fun norm(v: DoubleArray): Double = sqrt(dot(v, v))

        private fun dot(u: DoubleArray, v: DoubleArray): Double = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]

        private fun cross(u: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
            u[1] * v[2] - u[2] * v[1],
            u[2] * v[0] - u[0] * v[2],
            u[0] * v[1] - u[1] * v[0],
        )
    }
}
