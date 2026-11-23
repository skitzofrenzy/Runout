package com.runout.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.DisplacedBallPath
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.TrajectoryPath
import com.runout.engine.planner.HeatZoneMap
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.ProblemBallReport
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import com.runout.engine.planner.ShotUncertaintyResult
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val RailWoodOuterColor = Color(0xFF3E2415)
private val RailWoodInnerColor = Color(0xFF54321E)
private val CushionClothColor = Color(0xFF0D5C3A)
private val ClothBaizeColor = Color(0xFF137547)
private val TableMarkingColor = Color(0x55E2E8F0)
private val DiamondSightColor = Color(0xFFD6C7B2)
private val PocketHoleColor = Color(0xFF090D12)
private val PocketRimColor = Color(0xFF1E293B)

private val AimLineColor = Color(0xFFE0F2FE)
private val GhostBallStrokeColor = Color(0xFF7DD3FC)
private val PotLineColor = Color(0xFFFFD54F)
private val ScratchWarningColor = Color(0xFFEF4444)
private val ProblemBallWarningColor = Color(0xFFFF6B4A)
private val LandingCloudFillColor = Color(0x2438BDF8)
private val LandingCloudStrokeColor = Color(0xCC38BDF8)
private val LandingCloudDotColor = Color(0xD9BAE6FD)
private val HeatZoneHighColor = Color(0x4D10B981)
private val HeatZoneMediumColor = Color(0x42F59E0B)
private val HeatZoneLowColor = Color(0x38F87171)
private val DisturbedScatterColor = Color(0xFFF59E0B)

private data class TableCanvasGeometry(
    val railPaddingPx: Float,
    val pxPerMeter: Float,
    val playLeft: Float,
    val playTop: Float,
    val playWidth: Float,
    val playHeight: Float,
) {
    fun toCanvasOffset(siPos: Vec2): Offset = Offset(
        x = playLeft + (siPos.x * pxPerMeter).toFloat(),
        y = playTop + (siPos.y * pxPerMeter).toFloat(),
    )

    fun toTableMeters(offset: Offset): Vec2 = Vec2(
        x = ((offset.x - playLeft) / pxPerMeter).toDouble(),
        y = ((offset.y - playTop) / pxPerMeter).toDouble(),
    )
}

private fun computeTableCanvasGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    table: TableSpec,
): TableCanvasGeometry {
    val railPaddingPx = min(canvasWidth, canvasHeight) * 0.075f
    val availableWidth = (canvasWidth - 2f * railPaddingPx).coerceAtLeast(1f)
    val availableHeight = (canvasHeight - 2f * railPaddingPx).coerceAtLeast(1f)
    val pxPerMeter = min(
        availableWidth / table.lengthMeters.toFloat(),
        availableHeight / table.widthMeters.toFloat(),
    ).coerceAtLeast(1f)
    val playWidth = table.lengthMeters.toFloat() * pxPerMeter
    val playHeight = table.widthMeters.toFloat() * pxPerMeter
    val playLeft = (canvasWidth - playWidth) * 0.5f
    val playTop = (canvasHeight - playHeight) * 0.5f
    return TableCanvasGeometry(
        railPaddingPx = railPaddingPx,
        pxPerMeter = pxPerMeter,
        playLeft = playLeft,
        playTop = playTop,
        playWidth = playWidth,
        playHeight = playHeight,
    )
}

/**
 * Returns the UI trajectory colour for each [CueBallSpinPreset].
 */
fun spinColor(spin: CueBallSpinPreset): Color = when (spin) {
    CueBallSpinPreset.STUN -> Color(0xFF38BDF8)
    CueBallSpinPreset.FOLLOW -> Color(0xFF4ADE80)
    CueBallSpinPreset.DRAW -> Color(0xFFFF6B4A)
}

/**
 * Maps an engine [BallPaletteColor] into its Compose UI [Color].
 */
fun ballPaletteToComposeColor(paletteColor: BallPaletteColor): Color = when (paletteColor) {
    BallPaletteColor.WHITE -> Color(0xFFF8FAFC)
    BallPaletteColor.YELLOW -> Color(0xFFFACC15)
    BallPaletteColor.BLUE -> Color(0xFF2563EB)
    BallPaletteColor.RED -> Color(0xFFDC2626)
    BallPaletteColor.PURPLE -> Color(0xFF7C3AED)
    BallPaletteColor.ORANGE -> Color(0xFFEA580C)
    BallPaletteColor.GREEN -> Color(0xFF16A34A)
    BallPaletteColor.MAROON -> Color(0xFF881337)
    BallPaletteColor.BLACK -> Color(0xFF171717)
    BallPaletteColor.PINK -> Color(0xFFEC4899)
    BallPaletteColor.BROWN -> Color(0xFF92400E)
}

/**
 * Returns the UI colour for [ballNumber] (`0..15`) using [ballSet].
 */
fun ballBaseColor(
    ballNumber: Int,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
): Color = ballPaletteToComposeColor(ballSet.colorForBall(ballNumber))

/**
 * Renders a top-down pool or snooker table in accurate `length : width` proportions, mapping SI metres
 * from [layout], [selectedShot], and [activeRunoutPlan] to canvas pixels at the UI boundary and
 * supporting drag-and-drop ball placement when [onMoveBall] is provided.
 */
@Composable
fun PoolTableCanvas(
    layout: TableLayout,
    selectedShot: ShotRecommendation?,
    modifier: Modifier = Modifier,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
    onMoveBall: ((ballNumber: Int, newPosition: Vec2) -> Unit)? = null,
    onDragStateChanged: ((isDragging: Boolean) -> Unit)? = null,
    visibleSpins: Set<CueBallSpinPreset> = CueBallSpinPreset.entries.toSet(),
    activeRunoutPlan: RunoutPlan? = null,
    focusedStepIndex: Int? = null,
    isDraggingBall: Boolean = false,
    availablePockets: List<PocketId> = emptyList(),
    onSelectPocket: ((PocketId) -> Unit)? = null,
    showHeatZone: Boolean = false,
    showLandingCloud: Boolean = true,
    fitContainer: Boolean = false,
) {
    val table = layout.table
    val tableAspectRatio = (table.lengthMeters / table.widthMeters).toFloat()
        .takeIf { it.isFinite() && it > 0f } ?: 2f
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val latestLayout by rememberUpdatedState(layout)
    val latestOnMoveBall by rememberUpdatedState(onMoveBall)
    val latestOnDragStateChanged by rememberUpdatedState(onDragStateChanged)
    val latestAvailablePockets by rememberUpdatedState(availablePockets)
    val latestOnSelectPocket by rememberUpdatedState(onSelectPocket)

    val dragModifier = if (onMoveBall != null) {
        Modifier.pointerInput(true) {
            var draggedBallNumber: Int? = null
            var dragPosPx = Offset.Zero
            detectDragGestures(
                onDragStart = { startOffset ->
                    val activeLayout = latestLayout
                    val geom = computeTableCanvasGeometry(
                        canvasWidth = size.width.toFloat(),
                        canvasHeight = size.height.toFloat(),
                        table = activeLayout.table,
                    )
                    val hitRadiusPx = max(
                        36f,
                        (activeLayout.table.ballRadiusMeters * geom.pxPerMeter * 2.2).toFloat(),
                    )
                    val hitBall = activeLayout.balls
                        .map { ball ->
                            val center = geom.toCanvasOffset(ball.position)
                            val dist = hypot(startOffset.x - center.x, startOffset.y - center.y)
                            ball to dist
                        }
                        .filter { (_, dist) -> dist <= hitRadiusPx }
                        .minByOrNull { (_, dist) -> dist }
                        ?.first
                    if (hitBall != null) {
                        draggedBallNumber = hitBall.number
                        dragPosPx = geom.toCanvasOffset(hitBall.position)
                        latestOnDragStateChanged?.invoke(true)
                    } else {
                        draggedBallNumber = null
                    }
                },
                onDrag = { change, dragAmount ->
                    val ballNumber = draggedBallNumber ?: return@detectDragGestures
                    change.consume()
                    dragPosPx += dragAmount
                    val activeLayout = latestLayout
                    val geom = computeTableCanvasGeometry(
                        canvasWidth = size.width.toFloat(),
                        canvasHeight = size.height.toFloat(),
                        table = activeLayout.table,
                    )
                    latestOnMoveBall?.invoke(ballNumber, geom.toTableMeters(dragPosPx))
                },
                onDragEnd = {
                    if (draggedBallNumber != null) {
                        draggedBallNumber = null
                        latestOnDragStateChanged?.invoke(false)
                    }
                },
                onDragCancel = {
                    if (draggedBallNumber != null) {
                        draggedBallNumber = null
                        latestOnDragStateChanged?.invoke(false)
                    }
                },
            )
        }
    } else {
        Modifier
    }

    val pocketTapModifier = if (onSelectPocket != null) {
        Modifier.pointerInput(true) {
            detectTapGestures { tapOffset ->
                val validPockets = latestAvailablePockets
                if (validPockets.isEmpty()) return@detectTapGestures
                val activeLayout = latestLayout
                val geom = computeTableCanvasGeometry(
                    canvasWidth = size.width.toFloat(),
                    canvasHeight = size.height.toFloat(),
                    table = activeLayout.table,
                )
                val validSet = validPockets.toSet()
                val tappedPocket = activeLayout.table.pockets
                    .filter { it.id in validSet }
                    .map { pocket ->
                        val center = geom.toCanvasOffset(pocket.center)
                        val dist = hypot(tapOffset.x - center.x, tapOffset.y - center.y)
                        val hitRadius = max(
                            44f,
                            (pocket.acceptanceRadiusMeters * geom.pxPerMeter * 1.9).toFloat(),
                        )
                        Triple(pocket, dist, hitRadius)
                    }
                    .filter { (_, dist, hitRadius) -> dist <= hitRadius }
                    .minByOrNull { (_, dist, _) -> dist }
                    ?.first
                if (tappedPocket != null) {
                    latestOnSelectPocket?.invoke(tappedPocket.id)
                }
            }
        }
    } else {
        Modifier
    }

    val sizeModifier = if (fitContainer) {
        modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxWidth()
            .aspectRatio(tableAspectRatio)
    }

    Canvas(
        modifier = sizeModifier
            .then(dragModifier)
            .then(pocketTapModifier),
    ) {
        val geom = computeTableCanvasGeometry(
            canvasWidth = size.width,
            canvasHeight = size.height,
            table = table,
        )
        val railPaddingPx = geom.railPaddingPx
        val pxPerMeter = geom.pxPerMeter
        val playLeft = geom.playLeft
        val playTop = geom.playTop
        val playWidth = geom.playWidth
        val playHeight = geom.playHeight

        fun toCanvasOffset(siPos: Vec2): Offset = geom.toCanvasOffset(siPos)

        // 1. Wooden rails and cushion border
        val railOuterLeft = playLeft - railPaddingPx
        val railOuterTop = playTop - railPaddingPx
        val railOuterWidth = playWidth + 2f * railPaddingPx
        val railOuterHeight = playHeight + 2f * railPaddingPx
        val railCornerRadius = CornerRadius(railPaddingPx * 0.7f, railPaddingPx * 0.7f)
        drawRoundRect(
            color = RailWoodOuterColor,
            topLeft = Offset(railOuterLeft, railOuterTop),
            size = Size(railOuterWidth, railOuterHeight),
            cornerRadius = railCornerRadius,
        )
        val innerWoodInset = railPaddingPx * 0.18f
        drawRoundRect(
            color = RailWoodInnerColor,
            topLeft = Offset(railOuterLeft + innerWoodInset, railOuterTop + innerWoodInset),
            size = Size(railOuterWidth - 2f * innerWoodInset, railOuterHeight - 2f * innerWoodInset),
            cornerRadius = CornerRadius(railPaddingPx * 0.55f, railPaddingPx * 0.55f),
        )

        val cushionClothInset = railPaddingPx * 0.68f
        drawRoundRect(
            color = CushionClothColor,
            topLeft = Offset(playLeft - (railPaddingPx - cushionClothInset), playTop - (railPaddingPx - cushionClothInset)),
            size = Size(
                width = playWidth + 2f * (railPaddingPx - cushionClothInset),
                height = playHeight + 2f * (railPaddingPx - cushionClothInset),
            ),
            cornerRadius = CornerRadius(railPaddingPx * 0.2f, railPaddingPx * 0.2f),
        )

        // 2. Playing surface (within cushion noses: 0..lengthMeters, 0..widthMeters)
        drawRect(
            color = ClothBaizeColor,
            topLeft = Offset(playLeft, playTop),
            size = Size(playWidth, playHeight),
        )

        // 2b. Optional Milestone 3 position-value heat-zone grid over the cloth before balls/lines
        val heatZone = activeRunoutPlan?.nextBallHeatZone
        if (showHeatZone && heatZone != null) {
            drawHeatZoneOverlay(
                heatZone = heatZone,
                playLeft = playLeft,
                playTop = playTop,
                playWidth = playWidth,
                playHeight = playHeight,
            )
        }

        drawRect(
            color = Color(0x33000000),
            topLeft = Offset(playLeft, playTop),
            size = Size(playWidth, playHeight),
            style = Stroke(width = max(1f, pxPerMeter * 0.004f)),
        )

        // 3. Rail diamond sights (8 divisions along length, 4 along width)
        val diamondRadius = max(2f, railPaddingPx * 0.09f)
        val topRailSightY = playTop * 0.5f
        val bottomRailSightY = playTop + playHeight + (size.height - (playTop + playHeight)) * 0.5f
        for (i in listOf(1, 2, 3, 5, 6, 7)) {
            val sightX = playLeft + playWidth * (i / 8f)
            drawCircle(color = DiamondSightColor, radius = diamondRadius, center = Offset(sightX, topRailSightY))
            drawCircle(color = DiamondSightColor, radius = diamondRadius, center = Offset(sightX, bottomRailSightY))
        }
        val leftRailSightX = playLeft * 0.5f
        val rightRailSightX = playLeft + playWidth + (size.width - (playLeft + playWidth)) * 0.5f
        for (j in 1..3) {
            val sightY = playTop + playHeight * (j / 4f)
            drawCircle(color = DiamondSightColor, radius = diamondRadius, center = Offset(leftRailSightX, sightY))
            drawCircle(color = DiamondSightColor, radius = diamondRadius, center = Offset(rightRailSightX, sightY))
        }

        // 4. Head string (x = 0.25 * lengthMeters), head spot, and foot spot (x = 0.75 * lengthMeters)
        val headStringX = playLeft + (0.25 * table.lengthMeters * pxPerMeter).toFloat()
        drawLine(
            color = TableMarkingColor,
            start = Offset(headStringX, playTop),
            end = Offset(headStringX, playTop + playHeight),
            strokeWidth = max(1.2f, pxPerMeter * 0.003f),
        )
        val spotRadiusPx = max(2.5f, pxPerMeter * 0.008f)
        val headSpotCenter = toCanvasOffset(table.headSpot())
        val footSpotCenter = toCanvasOffset(table.footSpot())
        drawCircle(color = TableMarkingColor, radius = spotRadiusPx, center = headSpotCenter)
        drawCircle(color = TableMarkingColor, radius = spotRadiusPx, center = footSpotCenter)

        // 5. Six pockets (layout.table.pockets)
        val planSteps = activeRunoutPlan?.steps.orEmpty()
        val clampedStepIdx = focusedStepIndex
            ?.takeIf { planSteps.isNotEmpty() }
            ?.coerceIn(0, planSteps.lastIndex)
        val focusedStep = if (clampedStepIdx != null) planSteps[clampedStepIdx] else planSteps.firstOrNull()
        val targetPocketId = if (!isDraggingBall) (focusedStep?.pocketId ?: selectedShot?.pocket?.id) else null
        val selectablePocketsSet = if (!isDraggingBall) availablePockets.toSet() else emptySet()
        for (pocket in table.pockets) {
            val pocketCenter = toCanvasOffset(pocket.center)
            val pocketRadiusPx = (pocket.acceptanceRadiusMeters * pxPerMeter).toFloat()
            val isTargetPocket = pocket.id == targetPocketId
            val isSelectablePocket = pocket.id in selectablePocketsSet
            drawCircle(
                color = when {
                    isTargetPocket -> PotLineColor.copy(alpha = 0.45f)
                    isSelectablePocket -> PotLineColor.copy(alpha = 0.18f)
                    else -> PocketRimColor
                },
                radius = pocketRadiusPx * 1.12f,
                center = pocketCenter,
            )
            drawCircle(
                color = PocketHoleColor,
                radius = pocketRadiusPx,
                center = pocketCenter,
            )
            if (isTargetPocket) {
                drawCircle(
                    color = PotLineColor,
                    radius = pocketRadiusPx * 1.18f,
                    center = pocketCenter,
                    style = Stroke(width = max(2.2f, pxPerMeter * 0.0065f)),
                )
            } else if (isSelectablePocket) {
                drawCircle(
                    color = PotLineColor.copy(alpha = 0.55f),
                    radius = pocketRadiusPx * 1.14f,
                    center = pocketCenter,
                    style = Stroke(width = max(1.4f, pxPerMeter * 0.0042f)),
                )
            }
        }

        // 6. Strategy plan / selected shot overlays (suppressed while actively dragging for 60fps feedback)
        val ballRadiusPx = (table.ballRadiusMeters * pxPerMeter).toFloat()
        if (!isDraggingBall) {
            if (activeRunoutPlan != null && planSteps.isNotEmpty()) {
                drawRunoutPlanOverlay(
                    layout = layout,
                    plan = activeRunoutPlan,
                    focusedStepIndex = clampedStepIdx,
                    showLandingCloud = showLandingCloud,
                    ballRadiusPx = ballRadiusPx,
                    pxPerMeter = pxPerMeter,
                    textMeasurer = textMeasurer,
                    toCanvasOffset = ::toCanvasOffset,
                )
            } else if (selectedShot != null) {
                drawSelectedShotOverlay(
                    shot = selectedShot,
                    visibleSpins = visibleSpins,
                    ballRadiusMeters = table.ballRadiusMeters,
                    pxPerMeter = pxPerMeter,
                    toCanvasOffset = ::toCanvasOffset,
                )
            }
        }

        // 7. Balls on the playing surface
        val fontSizeSp = with(density) { (ballRadiusPx * 0.82f).toSp() }
        val targetBallNumber = if (!isDraggingBall) {
            focusedStep?.targetBall?.number ?: selectedShot?.targetBall?.number
        } else {
            null
        }
        for (ball in layout.balls) {
            val isTargetBall = ball.number == targetBallNumber
            drawPoolBall(
                ball = ball,
                ballColor = ballBaseColor(ball.number, ballSet),
                center = toCanvasOffset(ball.position),
                radiusPx = (ball.radiusMeters * pxPerMeter).toFloat(),
                isTargetBall = isTargetBall,
                textMeasurer = textMeasurer,
                fontSizeSp = fontSizeSp,
            )
        }

        // 8. Problem-ball dashed warning halos (drawn over balls so warnings remain crisp)
        if (!isDraggingBall && activeRunoutPlan != null && planSteps.isNotEmpty() && activeRunoutPlan.problemBalls.isNotEmpty()) {
            drawProblemBallHalos(
                layout = layout,
                problemBalls = activeRunoutPlan.problemBalls,
                ballRadiusPx = ballRadiusPx,
                toCanvasOffset = ::toCanvasOffset,
            )
        }
    }
}

private fun DrawScope.drawSelectedShotOverlay(
    shot: ShotRecommendation,
    visibleSpins: Set<CueBallSpinPreset>,
    ballRadiusMeters: Double,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val aim = shot.aim
    val cueStart = toCanvasOffset(aim.cueBallPos)
    val ghostCenter = toCanvasOffset(aim.ghostBallCenter)
    val objectPos = toCanvasOffset(aim.objectBallPos)
    val pocketPos = toCanvasOffset(aim.pocketAimPos)
    val ballRadiusPx = (ballRadiusMeters * pxPerMeter).toFloat()

    // Object-ball pot line (objectBallPos -> pocketAimPos) in bright yellow/gold
    drawLine(
        color = PotLineColor,
        start = objectPos,
        end = pocketPos,
        strokeWidth = max(2.5f, pxPerMeter * 0.008f),
        cap = StrokeCap.Round,
    )

    // Aim direction line (cueBallPos -> ghostBallCenter) in white/cyan dashed style
    val dashLen = max(6f, pxPerMeter * 0.028f)
    val gapLen = max(4f, pxPerMeter * 0.018f)
    drawLine(
        color = AimLineColor,
        start = cueStart,
        end = ghostCenter,
        strokeWidth = max(2.2f, pxPerMeter * 0.007f),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), 0f),
    )

    // Post-impact cue-ball trajectory polylines for visible spins.
    // Draw non-recommended spins first so the recommended spin renders cleanly on top.
    val orderedSpins = visibleSpins.sortedBy { it == shot.recommendedSpin }
    for (spin in orderedSpins) {
        val trajectory = shot.cueBallPaths[spin] ?: continue
        val isRecommended = spin == shot.recommendedSpin
        drawSpinTrajectory(
            trajectory = trajectory,
            isRecommended = isRecommended,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            toCanvasOffset = toCanvasOffset,
        )
    }

    // 2-ball position-play line from cue-ball stopping point to next target ghost ball
    shot.nextTargetPositionLine?.let { (cueStop, nextGhost) ->
        val stopOffset = toCanvasOffset(cueStop)
        val nextGhostOffset = toCanvasOffset(nextGhost)
        val nextDash = max(5f, pxPerMeter * 0.022f)
        val nextGap = max(4f, pxPerMeter * 0.016f)
        drawLine(
            color = PotLineColor.copy(alpha = 0.85f),
            start = stopOffset,
            end = nextGhostOffset,
            strokeWidth = max(2f, pxPerMeter * 0.006f),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(nextDash, nextGap), 0f),
        )
        drawCircle(
            color = PotLineColor,
            radius = ballRadiusPx * 0.92f,
            center = nextGhostOffset,
            style = Stroke(width = max(1.8f, pxPerMeter * 0.0055f)),
        )
    }

    // Ghost ball circle outline at ghostBallCenter
    drawGhostBallOutline(
        center = ghostCenter,
        ballRadiusPx = ballRadiusPx,
        strokeColor = GhostBallStrokeColor,
        pxPerMeter = pxPerMeter,
    )
}

private fun DrawScope.drawSpinTrajectory(
    trajectory: TrajectoryPath,
    isRecommended: Boolean,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val points = trajectory.points
    if (points.isEmpty()) return

    val baseColor = spinColor(trajectory.spin)
    val lineColor = if (isRecommended) baseColor else baseColor.copy(alpha = 0.72f)
    val strokeWidth = if (isRecommended) {
        max(3.2f, pxPerMeter * 0.010f)
    } else {
        max(1.8f, pxPerMeter * 0.0055f)
    }

    if (points.size >= 2) {
        val path = Path().apply {
            val first = toCanvasOffset(points.first())
            moveTo(first.x, first.y)
            for (idx in 1 until points.size) {
                val pt = toCanvasOffset(points[idx])
                lineTo(pt.x, pt.y)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }

    val endPoint = toCanvasOffset(points.last())
    if (trajectory.hitsScratchPocket != null) {
        val crossRadius = ballRadiusPx * 0.65f
        drawCircle(
            color = ScratchWarningColor.copy(alpha = 0.3f),
            radius = crossRadius * 1.25f,
            center = endPoint,
        )
        drawLine(
            color = ScratchWarningColor,
            start = endPoint + Offset(-crossRadius, -crossRadius),
            end = endPoint + Offset(crossRadius, crossRadius),
            strokeWidth = max(2.2f, strokeWidth * 0.85f),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ScratchWarningColor,
            start = endPoint + Offset(-crossRadius, crossRadius),
            end = endPoint + Offset(crossRadius, -crossRadius),
            strokeWidth = max(2.2f, strokeWidth * 0.85f),
            cap = StrokeCap.Round,
        )
    } else if (isRecommended) {
        drawCircle(
            color = baseColor.copy(alpha = 0.25f),
            radius = ballRadiusPx * 0.85f,
            center = endPoint,
        )
        drawCircle(
            color = baseColor,
            radius = ballRadiusPx * 0.85f,
            center = endPoint,
            style = Stroke(width = max(2f, pxPerMeter * 0.006f)),
        )
        drawCircle(
            color = baseColor,
            radius = max(3f, ballRadiusPx * 0.34f),
            center = endPoint,
        )
    } else {
        drawCircle(
            color = lineColor,
            radius = max(2.5f, ballRadiusPx * 0.26f),
            center = endPoint,
        )
    }
}

private fun DrawScope.drawPoolBall(
    ball: Ball,
    ballColor: Color,
    center: Offset,
    radiusPx: Float,
    isTargetBall: Boolean,
    textMeasurer: TextMeasurer,
    fontSizeSp: androidx.compose.ui.unit.TextUnit,
) {
    if (isTargetBall) {
        drawCircle(
            color = PotLineColor.copy(alpha = 0.35f),
            radius = radiusPx * 1.38f,
            center = center,
        )
        drawCircle(
            color = PotLineColor,
            radius = radiusPx * 1.32f,
            center = center,
            style = Stroke(width = max(1.8f, radiusPx * 0.14f)),
        )
    }

    // Drop shadow
    drawCircle(
        color = Color(0x59000000),
        radius = radiusPx,
        center = center + Offset(radiusPx * 0.12f, radiusPx * 0.16f),
    )

    val whiteBase = Color(0xFFF8FAFC)

    if (ball.isCueBall) {
        drawCircle(
            color = ballColor,
            radius = radiusPx,
            center = center,
        )
        drawCircle(
            color = Color(0xFFDC2626),
            radius = max(1.5f, radiusPx * 0.18f),
            center = center + Offset(radiusPx * 0.22f, -radiusPx * 0.18f),
        )
    } else if (ball.isStripe) {
        drawCircle(
            color = whiteBase,
            radius = radiusPx,
            center = center,
        )
        val circleClip = Path().apply {
            addOval(Rect(center = center, radius = radiusPx))
        }
        clipPath(circleClip) {
            val bandHalfHeight = radiusPx * 0.58f
            drawRect(
                color = ballColor,
                topLeft = Offset(center.x - radiusPx, center.y - bandHalfHeight),
                size = Size(radiusPx * 2f, bandHalfHeight * 2f),
            )
        }
    } else {
        drawCircle(
            color = ballColor,
            radius = radiusPx,
            center = center,
        )
    }

    // Number circle and label for object balls (1..15)
    if (!ball.isCueBall) {
        val numberPlateRadius = radiusPx * 0.50f
        drawCircle(
            color = whiteBase,
            radius = numberPlateRadius,
            center = center,
        )

        val label = ball.number.toString()
        val textLayout = textMeasurer.measure(
            text = label,
            style = TextStyle(
                color = Color(0xFF0F172A),
                fontSize = fontSizeSp,
                fontWeight = FontWeight.Bold,
            ),
        )
        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(
                x = center.x - textLayout.size.width * 0.5f,
                y = center.y - textLayout.size.height * 0.5f,
            ),
        )
    }

    // Outer contour ring
    drawCircle(
        color = Color(0xAA0F172A),
        radius = radiusPx,
        center = center,
        style = Stroke(width = max(1f, radiusPx * 0.08f)),
    )
}

private fun DrawScope.drawHeatZoneOverlay(
    heatZone: HeatZoneMap,
    playLeft: Float,
    playTop: Float,
    playWidth: Float,
    playHeight: Float,
) {
    val safeCols = heatZone.cols.coerceAtLeast(1)
    val safeRows = heatZone.rows.coerceAtLeast(1)
    val cellWidthPx = playWidth / safeCols.toFloat()
    val cellHeightPx = playHeight / safeRows.toFloat()

    clipRect(
        left = playLeft,
        top = playTop,
        right = playLeft + playWidth,
        bottom = playTop + playHeight,
    ) {
        for (cell in heatZone.cells) {
            val cellColor = when {
                cell.value >= 0.60 -> HeatZoneHighColor
                cell.value >= 0.30 -> HeatZoneMediumColor
                cell.value >= 0.05 -> HeatZoneLowColor
                else -> continue
            }
            drawRect(
                color = cellColor,
                topLeft = Offset(
                    x = playLeft + cell.col * cellWidthPx,
                    y = playTop + cell.row * cellHeightPx,
                ),
                size = Size(cellWidthPx + 0.5f, cellHeightPx + 0.5f),
            )
        }
    }
}

private fun DrawScope.drawRunoutPlanOverlay(
    layout: TableLayout,
    plan: RunoutPlan,
    focusedStepIndex: Int? = null,
    showLandingCloud: Boolean,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    textMeasurer: TextMeasurer,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val steps = plan.steps
    if (steps.isEmpty()) return

    val activeIdx = focusedStepIndex?.coerceIn(0, steps.lastIndex) ?: 0
    val primaryStep = steps[activeIdx]

    val cuePos = layout.cueBall()?.position
    val firstGhostCenter = toCanvasOffset(primaryStep.compensatedAim.compensatedGhostBallCenter)
    val firstObjectPos = toCanvasOffset(primaryStep.targetBall.position)
    val firstPocketPos = toCanvasOffset(primaryStep.pocketCenter)
    val dashLen = max(6f, pxPerMeter * 0.028f)
    val gapLen = max(4f, pxPerMeter * 0.018f)

    // Active step object-ball pot line
    drawLine(
        color = PotLineColor,
        start = firstObjectPos,
        end = firstPocketPos,
        strokeWidth = max(2.5f, pxPerMeter * 0.008f),
        cap = StrokeCap.Round,
    )

    // Active step spin-compensated aim line
    if (cuePos != null) {
        drawLine(
            color = AimLineColor,
            start = toCanvasOffset(cuePos),
            end = firstGhostCenter,
            strokeWidth = max(2.2f, pxPerMeter * 0.007f),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), 0f),
        )
    }

    drawGhostBallOutline(
        center = firstGhostCenter,
        ballRadiusPx = ballRadiusPx,
        strokeColor = GhostBallStrokeColor,
        pxPerMeter = pxPerMeter,
    )

    // Active step nominal post-impact cue-ball trajectory
    drawSpinTrajectory(
        trajectory = primaryStep.uncertainty.nominalTrajectory,
        isRecommended = true,
        ballRadiusPx = ballRadiusPx,
        pxPerMeter = pxPerMeter,
        toCanvasOffset = toCanvasOffset,
    )

    // Active step cue-ball landing cloud & 95% spread confidence ring
    if (showLandingCloud) {
        drawLandingCloudOverlay(
            uncertainty = primaryStep.uncertainty,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            toCanvasOffset = toCanvasOffset,
        )
    }

    // Scatter paths for any cluster balls bumped during this shot
    if (primaryStep.disturbedBalls.isNotEmpty()) {
        drawDisturbedBallsOverlay(
            disturbedBalls = primaryStep.disturbedBalls,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            alpha = 0.92f,
            toCanvasOffset = toCanvasOffset,
        )
    }

    // In focused single-step playback mode, show only the active step cleanly.
    if (focusedStepIndex != null) return

    // Multi-step overview sequence indicators for Step 2, Step 3, ...
    val stepDash = max(5f, pxPerMeter * 0.020f)
    val stepGap = max(4f, pxPerMeter * 0.016f)
    val stepEffect = PathEffect.dashPathEffect(floatArrayOf(stepDash, stepGap), 0f)
    for (idx in 1 until steps.size) {
        val step = steps[idx]
        val prevLanding = steps[idx - 1].uncertainty.meanLandingPosition
        val stepColor = multiStepAccentColor(step.stepNumber)
        val stepCueStart = toCanvasOffset(prevLanding)
        val stepGhost = toCanvasOffset(step.compensatedAim.compensatedGhostBallCenter)
        val stepObject = toCanvasOffset(step.targetBall.position)
        val stepPocket = toCanvasOffset(step.pocketCenter)

        // Cue-leave line from previous mean landing position to next compensated ghost ball
        drawLine(
            color = stepColor.copy(alpha = 0.80f),
            start = stepCueStart,
            end = stepGhost,
            strokeWidth = max(1.8f, pxPerMeter * 0.0055f),
            cap = StrokeCap.Round,
            pathEffect = stepEffect,
        )

        drawGhostBallOutline(
            center = stepGhost,
            ballRadiusPx = ballRadiusPx * 0.92f,
            strokeColor = stepColor.copy(alpha = 0.85f),
            pxPerMeter = pxPerMeter,
        )

        // Dashed object-to-pocket line for Step 2+
        drawLine(
            color = stepColor,
            start = stepObject,
            end = stepPocket,
            strokeWidth = max(2.0f, pxPerMeter * 0.006f),
            cap = StrokeCap.Round,
            pathEffect = stepEffect,
        )

        if (step.disturbedBalls.isNotEmpty()) {
            drawDisturbedBallsOverlay(
                disturbedBalls = step.disturbedBalls,
                ballRadiusPx = ballRadiusPx * 0.9f,
                pxPerMeter = pxPerMeter,
                alpha = 0.65f,
                toCanvasOffset = toCanvasOffset,
            )
        }

        // Step sequence badge next to the object ball
        drawStepNumberBadge(
            step = step,
            ballCenter = stepObject,
            ballRadiusPx = ballRadiusPx,
            badgeColor = stepColor,
            textMeasurer = textMeasurer,
        )
    }
}

private fun DrawScope.drawDisturbedBallsOverlay(
    disturbedBalls: List<DisplacedBallPath>,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    alpha: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val dash = max(5f, pxPerMeter * 0.018f)
    val gap = max(4f, pxPerMeter * 0.014f)
    val scatterEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f)
    val strokeW = max(1.8f, pxPerMeter * 0.0052f)
    val color = DisturbedScatterColor.copy(alpha = alpha)

    for (path in disturbedBalls) {
        if (path.points.size < 2) continue
        val pathObj = Path().apply {
            val first = toCanvasOffset(path.points.first())
            moveTo(first.x, first.y)
            for (i in 1 until path.points.size) {
                val pt = toCanvasOffset(path.points[i])
                lineTo(pt.x, pt.y)
            }
        }
        drawPath(
            path = pathObj,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, pathEffect = scatterEffect),
        )
        val endOffset = toCanvasOffset(path.points.last())
        drawCircle(
            color = color.copy(alpha = alpha * 0.20f),
            radius = ballRadiusPx * 0.85f,
            center = endOffset,
        )
        drawCircle(
            color = color,
            radius = ballRadiusPx * 0.85f,
            center = endOffset,
            style = Stroke(width = max(1.4f, strokeW * 0.85f)),
        )
    }
}

private fun DrawScope.drawGhostBallOutline(
    center: Offset,
    ballRadiusPx: Float,
    strokeColor: Color,
    pxPerMeter: Float,
) {
    drawCircle(
        color = strokeColor.copy(alpha = 0.16f),
        radius = ballRadiusPx,
        center = center,
    )
    drawCircle(
        color = strokeColor,
        radius = ballRadiusPx,
        center = center,
        style = Stroke(
            width = max(1.6f, pxPerMeter * 0.0052f),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(max(4f, ballRadiusPx * 0.45f), max(3f, ballRadiusPx * 0.30f)),
                0f,
            ),
        ),
    )
    drawCircle(
        color = AimLineColor,
        radius = max(2f, ballRadiusPx * 0.16f),
        center = center,
    )
}

private fun DrawScope.drawStepNumberBadge(
    step: PlannedShotStep,
    ballCenter: Offset,
    ballRadiusPx: Float,
    badgeColor: Color,
    textMeasurer: TextMeasurer,
) {
    val badgeRadius = max(7f, ballRadiusPx * 0.56f)
    val badgeCenter = ballCenter + Offset(ballRadiusPx * 0.95f, -ballRadiusPx * 0.95f)
    drawCircle(
        color = Color(0xFF0B1120),
        radius = badgeRadius + 1.5f,
        center = badgeCenter,
    )
    drawCircle(
        color = badgeColor,
        radius = badgeRadius,
        center = badgeCenter,
    )
    val labelLayout = textMeasurer.measure(
        text = step.stepNumber.toString(),
        style = TextStyle(
            color = Color(0xFF082F49),
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
        ),
    )
    drawText(
        textLayoutResult = labelLayout,
        topLeft = Offset(
            x = badgeCenter.x - labelLayout.size.width * 0.5f,
            y = badgeCenter.y - labelLayout.size.height * 0.5f,
        ),
    )
}

private fun DrawScope.drawLandingCloudOverlay(
    uncertainty: ShotUncertaintyResult,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val meanCenter = toCanvasOffset(uncertainty.meanLandingPosition)
    val spreadRadiusPx = (uncertainty.landingSpreadRadiusMeters * pxPerMeter)
        .toFloat()
        .coerceAtLeast(ballRadiusPx * 0.65f)

    // Translucent 95% confidence spread circle
    drawCircle(
        color = LandingCloudFillColor,
        radius = spreadRadiusPx,
        center = meanCenter,
    )
    val ringDash = max(5f, ballRadiusPx * 0.45f)
    val ringGap = max(4f, ballRadiusPx * 0.30f)
    drawCircle(
        color = LandingCloudStrokeColor,
        radius = spreadRadiusPx,
        center = meanCenter,
        style = Stroke(
            width = max(1.6f, pxPerMeter * 0.005f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(ringDash, ringGap), 0f),
        ),
    )

    // Individual sigma-point landing cloud dots
    val dotRadius = max(2.2f, ballRadiusPx * 0.17f)
    for (pt in uncertainty.landingCloud) {
        val ptOffset = toCanvasOffset(pt)
        drawCircle(
            color = Color(0xAA0C4A6E),
            radius = dotRadius + 0.8f,
            center = ptOffset,
        )
        drawCircle(
            color = LandingCloudDotColor,
            radius = dotRadius,
            center = ptOffset,
        )
    }

    // Mean landing center marker
    drawCircle(
        color = Color(0xFF38BDF8),
        radius = max(3f, ballRadiusPx * 0.25f),
        center = meanCenter,
        style = Stroke(width = max(1.5f, pxPerMeter * 0.0045f)),
    )
}

private fun DrawScope.drawProblemBallHalos(
    layout: TableLayout,
    problemBalls: List<ProblemBallReport>,
    ballRadiusPx: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val dashLen = max(4f, ballRadiusPx * 0.48f)
    val gapLen = max(3f, ballRadiusPx * 0.32f)
    val haloEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), 0f)
    val haloRadius = ballRadiusPx * 1.48f

    for (report in problemBalls) {
        val pos = layout.ballByNumber(report.ballNumber)?.position ?: report.positionMeters
        val center = toCanvasOffset(pos)
        drawCircle(
            color = ProblemBallWarningColor.copy(alpha = 0.16f),
            radius = haloRadius,
            center = center,
        )
        drawCircle(
            color = ProblemBallWarningColor,
            radius = haloRadius,
            center = center,
            style = Stroke(
                width = max(2f, ballRadiusPx * 0.15f),
                pathEffect = haloEffect,
            ),
        )
    }
}

private fun multiStepAccentColor(stepNumber: Int): Color = when (stepNumber) {
    2 -> Color(0xFF38BDF8)
    3 -> Color(0xFF4ADE80)
    4 -> Color(0xFFC084FC)
    5 -> Color(0xFFFBBF24)
    else -> Color(0xFFFB7185)
}

/**
 * Interactive 2D cue-tip english selector displaying a front view of the cue ball with crosshairs,
 * the `0.50 R` miscue-safe boundary ring, and a draggable/tappable contact dot mapped to [TipOffset].
 */
@Composable
fun CueTipSelectorCanvas(
    tipOffset: TipOffset,
    onUpdateTip: (TipOffset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val latestTip by rememberUpdatedState(tipOffset)
    val latestOnUpdateTip by rememberUpdatedState(onUpdateTip)

    fun updateFromPointer(pointer: Offset, widthPx: Float, heightPx: Float) {
        val ballRadiusPx = (min(widthPx, heightPx) * 0.43f).coerceAtLeast(1f)
        val centerX = widthPx * 0.5f
        val centerY = heightPx * 0.5f
        val rawSide = ((pointer.x - centerX) / ballRadiusPx).toDouble()
        val rawVert = (-(pointer.y - centerY) / ballRadiusPx).toDouble()
        val maxRatio = TipOffset.MAX_SAFE_OFFSET_RATIO
        val mag = hypot(rawSide, rawVert)
        val (clampedSide, clampedVert) = if (mag > maxRatio && mag > 1e-9) {
            (rawSide * maxRatio / mag) to (rawVert * maxRatio / mag)
        } else {
            rawSide to rawVert
        }
        latestOnUpdateTip(
            TipOffset(
                sideRatio = clampedSide,
                verticalRatio = clampedVert,
                elevationRadians = latestTip.elevationRadians,
            ),
        )
    }

    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 148.dp, minHeight = 148.dp)
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    updateFromPointer(offset, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        updateFromPointer(startOffset, size.width.toFloat(), size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        updateFromPointer(change.position, size.width.toFloat(), size.height.toFloat())
                    },
                )
            },
    ) {
        val ballRadiusPx = min(size.width, size.height) * 0.43f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val safeRadiusPx = ballRadiusPx * TipOffset.MAX_SAFE_OFFSET_RATIO.toFloat()

        // Outer cue-ball shadow & ivory sphere
        drawCircle(
            color = Color(0x4D000000),
            radius = ballRadiusPx,
            center = center + Offset(ballRadiusPx * 0.06f, ballRadiusPx * 0.08f),
        )
        drawCircle(
            color = Color(0xFFF8FAFC),
            radius = ballRadiusPx,
            center = center,
        )
        drawCircle(
            color = Color(0xFF94A3B8),
            radius = ballRadiusPx,
            center = center,
            style = Stroke(width = max(1.5f, ballRadiusPx * 0.03f)),
        )

        // Safe-offset 0.50 R disk and boundary ring
        drawCircle(
            color = Color(0x140284C7),
            radius = safeRadiusPx,
            center = center,
        )
        val dash = max(5f, ballRadiusPx * 0.09f)
        val gap = max(4f, ballRadiusPx * 0.07f)
        drawCircle(
            color = Color(0xFF0284C7),
            radius = safeRadiusPx,
            center = center,
            style = Stroke(
                width = max(1.6f, ballRadiusPx * 0.028f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f),
            ),
        )

        // Crosshairs
        val crosshairColor = Color(0x70475569)
        val crosshairStroke = max(1.2f, ballRadiusPx * 0.02f)
        drawLine(
            color = crosshairColor,
            start = Offset(center.x - ballRadiusPx * 0.88f, center.y),
            end = Offset(center.x + ballRadiusPx * 0.88f, center.y),
            strokeWidth = crosshairStroke,
        )
        drawLine(
            color = crosshairColor,
            start = Offset(center.x, center.y - ballRadiusPx * 0.88f),
            end = Offset(center.x, center.y + ballRadiusPx * 0.88f),
            strokeWidth = crosshairStroke,
        )
        drawCircle(
            color = Color(0xFF64748B),
            radius = max(2f, ballRadiusPx * 0.035f),
            center = center,
        )

        // Axis direction cues (F = Follow, D = Draw, L = Left, R = Right)
        val labelStyle = TextStyle(
            color = Color(0xFF475569),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        val axisMarkers = listOf(
            "F" to Offset(center.x, center.y - ballRadiusPx * 0.76f),
            "D" to Offset(center.x, center.y + ballRadiusPx * 0.76f),
            "L" to Offset(center.x - ballRadiusPx * 0.76f, center.y),
            "R" to Offset(center.x + ballRadiusPx * 0.76f, center.y),
        )
        for ((text, pos) in axisMarkers) {
            val measured = textMeasurer.measure(text = text, style = labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = pos.x - measured.size.width * 0.5f,
                    y = pos.y - measured.size.height * 0.5f,
                ),
            )
        }

        // Red/blue cue-tip contact dot at (clampedSideRatio, clampedVerticalRatio)
        val contactCenter = Offset(
            x = center.x + (tipOffset.clampedSideRatio.toFloat() * ballRadiusPx),
            y = center.y - (tipOffset.clampedVerticalRatio.toFloat() * ballRadiusPx),
        )
        val tipDotRadius = max(5f, ballRadiusPx * 0.13f)
        drawCircle(
            color = Color(0x382563EB),
            radius = tipDotRadius * 1.65f,
            center = contactCenter,
        )
        drawCircle(
            color = Color(0xFF2563EB),
            radius = tipDotRadius,
            center = contactCenter,
        )
        drawCircle(
            color = Color(0xFFDC2626),
            radius = tipDotRadius * 0.56f,
            center = contactCenter,
        )
    }
}
