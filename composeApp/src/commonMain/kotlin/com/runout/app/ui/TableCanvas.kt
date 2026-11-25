package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runout.app.navigation.PlatformBackHandler
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.DisplacedBallPath
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.PathWaypoint
import com.runout.engine.physics.PathWaypointKind
import com.runout.engine.physics.ShotPathWaypoints
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.TrajectoryPath
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackLineRole
import com.runout.engine.planner.HeatZoneGenerator
import com.runout.engine.planner.HeatZoneMap
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.PocketVisibilityRegion
import com.runout.engine.planner.ProblemBallReport
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import com.runout.engine.planner.ShotUncertaintyResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val RailWoodOuterColor = Color(0xFF3E2415)
private val RailWoodInnerColor = Color(0xFF54321E)
private val ClothBaizeColor = Color(0xFF137547)
private val CushionClothColor = cushionShadeOf(ClothBaizeColor)
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

internal data class TableCanvasGeometry(
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

/**
 * Fits [table] and its rail band into the canvas. The band is 7.5 % of the short canvas side, widened (and the table
 * shrunk to match) when a pocket's hole would not fit inside it, see [requiredRailBandMeters].
 */
internal fun computeTableCanvasGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    table: TableSpec,
): TableCanvasGeometry {
    val basePaddingPx = min(canvasWidth, canvasHeight) * 0.075f
    val baseAvailableWidth = (canvasWidth - 2f * basePaddingPx).coerceAtLeast(1f)
    val baseAvailableHeight = (canvasHeight - 2f * basePaddingPx).coerceAtLeast(1f)
    val basePxPerMeter = min(
        baseAvailableWidth / table.lengthMeters.toFloat(),
        baseAvailableHeight / table.widthMeters.toFloat(),
    ).coerceAtLeast(1f)
    val bandMeters = max(basePaddingPx / basePxPerMeter, requiredRailBandMeters(table).toFloat())
    val pxPerMeter = min(
        canvasWidth / (table.lengthMeters.toFloat() + 2f * bandMeters),
        canvasHeight / (table.widthMeters.toFloat() + 2f * bandMeters),
    ).coerceAtLeast(1f)
    val railPaddingPx = bandMeters * pxPerMeter
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
 * Computes the exact top-left `y` coordinate for [drawText] so uppercase digits (`0..9`) sit
 * optically dead-center vertically around [centerY], independent of platform font padding or descent.
 */
internal fun centeredDigitTopY(
    centerY: Float,
    layout: TextLayoutResult,
    fontSizePx: Float,
): Float {
    val capHeightPx = fontSizePx * 0.72f
    return centerY - layout.firstBaseline + capHeightPx * 0.5f
}

/**
 * Crisp Canvas-rendered pool ball swatch (`0..15`) with optically centered vertical numbering,
 * shared across reassign lists, photo review cards, and ball selection grids.
 */
@Composable
fun BallNumberBadge(
    ballNumber: Int,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
    sizeDp: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Canvas(modifier = modifier.size(sizeDp)) {
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val radiusPx = (min(size.width, size.height) * 0.46f).coerceAtLeast(1f)
        val ballColor = ballBaseColor(ballNumber, ballSet)
        val whiteBase = Color(0xFFF8FAFC)
        val isStripe = ballNumber in 9..15

        drawCircle(
            color = Color(0x40000000),
            radius = radiusPx,
            center = center + Offset(radiusPx * 0.06f, radiusPx * 0.10f),
        )

        if (ballNumber == 0) {
            drawCircle(color = whiteBase, radius = radiusPx, center = center)
            drawCircle(
                color = Color(0xFFDC2626),
                radius = max(1.4f, radiusPx * 0.19f),
                center = center + Offset(radiusPx * 0.22f, -radiusPx * 0.18f),
            )
        } else if (isStripe) {
            drawCircle(color = whiteBase, radius = radiusPx, center = center)
            val clipCircle = Path().apply { addOval(Rect(center = center, radius = radiusPx)) }
            clipPath(clipCircle) {
                val bandHalf = radiusPx * 0.58f
                drawRect(
                    color = ballColor,
                    topLeft = Offset(center.x - radiusPx, center.y - bandHalf),
                    size = Size(radiusPx * 2f, bandHalf * 2f),
                )
            }
        } else {
            drawCircle(color = ballColor, radius = radiusPx, center = center)
        }

        if (ballNumber != 0) {
            val plateRadius = radiusPx * 0.56f
            drawCircle(color = whiteBase, radius = plateRadius, center = center)
            val fontSizePx = (radiusPx * (if (ballNumber >= 10) 0.66f else 0.74f)).coerceAtLeast(6f)
            val fontSizeSp = with(density) { fontSizePx.toSp() }
            val labelLayout = textMeasurer.measure(
                text = ballNumber.toString(),
                style = TextStyle(
                    color = Color(0xFF0F172A),
                    fontSize = fontSizeSp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(
                    x = center.x - labelLayout.size.width * 0.5f,
                    y = centeredDigitTopY(center.y, labelLayout, fontSizePx),
                ),
            )
        }

        drawCircle(
            color = Color(0xAA0F172A),
            radius = radiusPx,
            center = center,
            style = Stroke(width = max(1f, radiusPx * 0.08f)),
        )
    }
}

internal const val MIN_TABLE_ZOOM: Float = 1.0f
internal const val MAX_TABLE_ZOOM: Float = 3.5f

internal fun clampTablePanOffset(
    rawPan: Offset,
    zoom: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): Offset {
    if (zoom <= 1.001f) return Offset.Zero
    val maxPanX = (canvasWidth * (zoom - 1f) * 0.5f).coerceAtLeast(0f)
    val maxPanY = (canvasHeight * (zoom - 1f) * 0.5f).coerceAtLeast(0f)
    return Offset(
        x = rawPan.x.coerceIn(-maxPanX, maxPanX),
        y = rawPan.y.coerceIn(-maxPanY, maxPanY),
    )
}

internal fun screenToBaseCanvasOffset(
    screenOffset: Offset,
    canvasSize: Size,
    zoom: Float,
    panOffset: Offset,
): Offset {
    val cx = canvasSize.width * 0.5f
    val cy = canvasSize.height * 0.5f
    val safeZoom = zoom.coerceAtLeast(1f)
    return Offset(
        x = cx + (screenOffset.x - cx - panOffset.x) / safeZoom,
        y = cy + (screenOffset.y - cy - panOffset.y) / safeZoom,
    )
}

internal fun baseToScreenCanvasOffset(
    baseOffset: Offset,
    canvasSize: Size,
    zoom: Float,
    panOffset: Offset,
): Offset {
    val cx = canvasSize.width * 0.5f
    val cy = canvasSize.height * 0.5f
    return Offset(
        x = cx + (baseOffset.x - cx) * zoom + panOffset.x,
        y = cy + (baseOffset.y - cy) * zoom + panOffset.y,
    )
}

/**
 * Renders a top-down pool or snooker table in accurate `length : width` proportions, mapping SI metres
 * from [layout], [selectedShot], and [activeRunoutPlan] to canvas pixels at the UI boundary and
 * supporting pinch-to-zoom / pan (`1.0x..3.5x`), corner zoom controls, and precision loupe ball dragging.
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
    onTapCueBall: (() -> Unit)? = null,
    highlightCueBall: Boolean = false,
    showHeatZone: Boolean = false,
    showLandingCloud: Boolean = true,
    fitContainer: Boolean = false,
    displayMode: AppDisplayMode = AppDisplayMode.SIMPLE,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    fallbackAdvice: FallbackAdvice? = null,
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
    val latestOnTapCueBall by rememberUpdatedState(onTapCueBall)

    // Tappable physics waypoints (spin transitions, contacts, cushions, rest) for the active plan step
    val tooltipSteps = activeRunoutPlan?.steps.orEmpty()
    val tooltipStep = if (!isDraggingBall && tooltipSteps.isNotEmpty()) {
        tooltipSteps[(focusedStepIndex ?: 0).coerceIn(0, tooltipSteps.lastIndex)]
    } else {
        null
    }
    val pathWaypoints = remember(tooltipStep, table) {
        tooltipStep?.let { step ->
            ShotPathWaypoints.build(
                aim = step.compensatedAim.geometricAim,
                cueSpeedMps = step.cueSpeedMps,
                tip = step.recommendedTip,
                table = table,
                simulation = step.uncertainty.postShotSimulation,
                targetBallNumber = step.targetBall.number,
                speedUnits = step.recommendedSpeedUnits,
            )
        } ?: emptyList()
    }
    val latestWaypoints by rememberUpdatedState(pathWaypoints)
    // The selection remembers which waypoint list it belongs to, so it is dropped as soon as the shot changes
    // (another step, a sandbox tip or speed edit, a ball move) without waiting for a later recomposition.
    var waypointSelection by remember { mutableStateOf<PathWaypointSelection?>(null) }
    val selectedWaypointIndex = waypointSelection
        ?.takeIf { it.waypoints === pathWaypoints }
        ?.index
        ?.takeIf { it in pathWaypoints.indices }
    val activeWaypointIndex = selectedWaypointIndex
    // Back closes the open path inspector card before anything else on the screen reacts.
    PlatformBackHandler(enabled = selectedWaypointIndex != null) { waypointSelection = null }
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var lastCanvasSize by remember { mutableStateOf(Size.Zero) }
    var activeDraggedBallNumber by remember { mutableStateOf<Int?>(null) }
    var activeDragCanvasPos by remember { mutableStateOf<Offset?>(null) }

    fun applyStepZoom(targetZoom: Float) {
        val clamped = targetZoom.coerceIn(MIN_TABLE_ZOOM, MAX_TABLE_ZOOM)
        if (clamped <= 1.001f || lastCanvasSize.width <= 1f || lastCanvasSize.height <= 1f) {
            zoomScale = 1f
            panOffset = Offset.Zero
            return
        }
        val oldZoom = zoomScale
        val center = Offset(lastCanvasSize.width * 0.5f, lastCanvasSize.height * 0.5f)
        val rawPan = panOffset * (clamped / oldZoom.coerceAtLeast(1f))
        zoomScale = clamped
        panOffset = clampTablePanOffset(rawPan, clamped, center.x * 2f, center.y * 2f)
    }

    // Current rectangle of the path inspector card (null while it is hidden), kept outside snapshot state because
    // only the gesture loop reads it.
    val calloutCardRect = remember { arrayOfNulls<Rect>(1) }

    val interactiveGestureModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A press that starts on the card belongs to the card: it must not dismiss it, pick a waypoint or
            // pocket, drag a ball or pan the table. The card also consumes pointer input itself; this guards the
            // canvas in case an event still reaches it.
            if (isPointOnCallout(calloutCardRect[0], down.position)) return@awaitEachGesture
            val cSize = Size(size.width.toFloat(), size.height.toFloat())
            val startLayout = latestLayout
            val startGeom = computeTableCanvasGeometry(cSize.width, cSize.height, startLayout.table)

            val hitBall = if (latestOnMoveBall != null || latestOnTapCueBall != null) {
                startLayout.balls
                    .map { ball ->
                        val screenPos = baseToScreenCanvasOffset(
                            startGeom.toCanvasOffset(ball.position),
                            cSize,
                            zoomScale,
                            panOffset,
                        )
                        val dist = hypot(down.position.x - screenPos.x, down.position.y - screenPos.y)
                        val hitR = max(
                            BALL_HIT_MIN_RADIUS_DP.dp.toPx(),
                            (ball.radiusMeters * startGeom.pxPerMeter * zoomScale * 2.0).toFloat(),
                        ).coerceAtMost(BALL_HIT_MAX_RADIUS_DP.dp.toPx())
                        Triple(ball, dist, hitR)
                    }
                    .filter { (_, dist, hitR) -> dist <= hitR }
                    .minByOrNull { (_, dist, _) -> dist }
                    ?.first
            } else {
                null
            }
            val initialBallPosMeters = hitBall?.position
            val touchSlop = viewConfiguration.touchSlop
            // Screen position of the pressed ball; a drag moves it by the finger's displacement from the press.
            val ballStartScreen = hitBall?.let { ball ->
                baseToScreenCanvasOffset(startGeom.toCanvasOffset(ball.position), cSize, zoomScale, panOffset)
            }
            var isBallDragging = false
            var dragWasActive = false
            var isPanning = false
            var enteredPinch = false
            var maxSingleMovePx = 0f
            var lastSinglePos = down.position

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break

                    if (pressed.size >= 2) {
                        if (isBallDragging && maxSingleMovePx < touchSlop * 2.5f) {
                            if (hitBall != null && initialBallPosMeters != null) {
                                latestOnMoveBall?.invoke(hitBall.number, initialBallPosMeters)
                            }
                            isBallDragging = false
                            activeDraggedBallNumber = null
                            activeDragCanvasPos = null
                            latestOnDragStateChanged?.invoke(false)
                        }
                        enteredPinch = true
                        if (!isBallDragging) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val oldZoom = zoomScale
                            val newZoom = (oldZoom * zoomChange).coerceIn(MIN_TABLE_ZOOM, MAX_TABLE_ZOOM)
                            val center = Offset(cSize.width * 0.5f, cSize.height * 0.5f)
                            val focal = if (centroid != Offset.Unspecified) centroid else center
                            val rel = focal - center
                            val rawPan = rel + panChange - (rel - panOffset) * (newZoom / oldZoom.coerceAtLeast(1f))
                            zoomScale = newZoom
                            panOffset = clampTablePanOffset(rawPan, newZoom, cSize.width, cSize.height)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } else if (!enteredPinch) {
                        val change = pressed.first()
                        lastSinglePos = change.position
                        val totalMove = (change.position - down.position).getDistance()
                        if (totalMove > maxSingleMovePx) maxSingleMovePx = totalMove

                        if (hitBall != null && latestOnMoveBall != null && ballStartScreen != null) {
                            // The same threshold separates a tap from a drag, so a wobbling tap never moves a ball.
                            if (isBallDragging || maxSingleMovePx > touchSlop) {
                                if (!isBallDragging) {
                                    isBallDragging = true
                                    activeDraggedBallNumber = hitBall.number
                                    latestOnDragStateChanged?.invoke(true)
                                }
                                change.consume()
                                activeDragCanvasPos = change.position
                                val curGeom = computeTableCanvasGeometry(cSize.width, cSize.height, latestLayout.table)
                                val target = ballStartScreen + (change.position - down.position)
                                val baseOffset = screenToBaseCanvasOffset(target, cSize, zoomScale, panOffset)
                                latestOnMoveBall?.invoke(hitBall.number, curGeom.toTableMeters(baseOffset))
                            }
                        } else if (zoomScale > 1.01f) {
                            if (maxSingleMovePx > touchSlop * 0.6f) {
                                isPanning = true
                                val delta = change.position - change.previousPosition
                                change.consume()
                                panOffset = clampTablePanOffset(panOffset + delta, zoomScale, cSize.width, cSize.height)
                            }
                        }
                    }
                }
            } finally {
                // Runs on release, cancellation and when the canvas leaves composition mid-drag, so the
                // application's dragging flag cannot stay set.
                if (isBallDragging) {
                    dragWasActive = true
                    isBallDragging = false
                    activeDraggedBallNumber = null
                    activeDragCanvasPos = null
                    latestOnDragStateChanged?.invoke(false)
                }
            }

            if (!dragWasActive && !enteredPinch && !isPanning && maxSingleMovePx <= touchSlop && !down.isConsumed) {
                val activeLayout = latestLayout
                val curGeom = computeTableCanvasGeometry(cSize.width, cSize.height, activeLayout.table)
                var handledTap = false
                if (latestOnTapCueBall != null) {
                    val cueBall = activeLayout.cueBall()
                    if (cueBall != null) {
                        val cueScreen = baseToScreenCanvasOffset(
                            curGeom.toCanvasOffset(cueBall.position),
                            cSize,
                            zoomScale,
                            panOffset,
                        )
                        val cueDist = hypot(lastSinglePos.x - cueScreen.x, lastSinglePos.y - cueScreen.y)
                        val cueHitR = max(
                            CUE_BALL_HIT_MIN_RADIUS_DP.dp.toPx(),
                            (cueBall.radiusMeters * curGeom.pxPerMeter * zoomScale * 2.3).toFloat(),
                        ).coerceAtMost(CUE_BALL_HIT_MAX_RADIUS_DP.dp.toPx())
                        if (cueDist <= cueHitR) {
                            latestOnTapCueBall?.invoke()
                            handledTap = true
                        }
                    }
                }
                if (!handledTap) {
                    val tapPoint = lastSinglePos
                    val wps = latestWaypoints
                    val waypointHit = pickPathWaypointIndex(
                        screenPoints = wps.map { wp ->
                            if (wp.kind == PathWaypointKind.CUE_START) {
                                null
                            } else {
                                baseToScreenCanvasOffset(
                                    curGeom.toCanvasOffset(wp.position),
                                    cSize,
                                    zoomScale,
                                    panOffset,
                                )
                            }
                        },
                        tap = tapPoint,
                        radiusPx = PATH_WAYPOINT_TAP_RADIUS_DP.dp.toPx(),
                    )
                    val pocketHit = if (latestOnSelectPocket != null && latestAvailablePockets.isNotEmpty()) {
                        val validSet = latestAvailablePockets.toSet()
                        activeLayout.table.pockets
                            .filter { it.id in validSet }
                            .map { pocket ->
                                val screenPocket = baseToScreenCanvasOffset(
                                    curGeom.toCanvasOffset(pocketTapPoint(pocket)),
                                    cSize,
                                    zoomScale,
                                    panOffset,
                                )
                                val dist = hypot(tapPoint.x - screenPocket.x, tapPoint.y - screenPocket.y)
                                Triple(pocket, dist, POCKET_TAP_RADIUS_DP.dp.toPx())
                            }
                            .filter { (_, dist, hitR) -> dist <= hitR }
                            .minByOrNull { (_, dist, _) -> dist }
                    } else {
                        null
                    }
                    // Only a tap on the cue ball is handled above; a tap on any other ball falls through to the
                    // path waypoint and pocket choice below. A waypoint gives way to a pocket only when the tap
                    // is strictly closer to that pocket's centre than to the waypoint.
                    if (waypointHit != null && (pocketHit == null || waypointHit.second <= pocketHit.second)) {
                        waypointSelection = PathWaypointSelection(wps, waypointHit.first)
                        handledTap = true
                    } else if (pocketHit != null) {
                        latestOnSelectPocket?.invoke(pocketHit.first.id)
                        handledTap = true
                    }
                }
                if (!handledTap) {
                    waypointSelection = null
                }
            }
        }
    }

    val sizeModifier = if (fitContainer) {
        modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxWidth()
            .aspectRatio(tableAspectRatio)
    }

    Box(
        modifier = sizeModifier.clipToBounds(),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(interactiveGestureModifier),
        ) {
            lastCanvasSize = size
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

            val tablePalette = TableDrawPalette(
                woodOuter = RailWoodOuterColor,
                woodInner = RailWoodInnerColor,
                cloth = ClothBaizeColor,
                cushion = CushionClothColor,
                throat = PocketHoleColor,
                holeRim = PocketRimColor,
            )

            fun DrawScope.renderTableLayer() {
                // 1. Wooden rails, cloth (the nose rectangle 0..lengthMeters x 0..widthMeters) and pocket openings
                drawTableBody(table, geom, tablePalette)

        // Shared active step & pocket visibility for cloth heat-zone, pockets, and landing-cloud occlusion
        val planSteps = activeRunoutPlan?.steps.orEmpty()
        val clampedStepIdx = focusedStepIndex
            ?.takeIf { planSteps.isNotEmpty() }
            ?.coerceIn(0, planSteps.lastIndex)
        val focusedStep = if (clampedStepIdx != null) planSteps[clampedStepIdx] else planSteps.firstOrNull()
        val activeVisibility = if (!isDraggingBall && activeRunoutPlan != null && planSteps.isNotEmpty()) {
            resolveStepNextVisibility(layout, activeRunoutPlan, clampedStepIdx ?: 0)
        } else {
            null
        }

        // 2b. Optional Milestone 3 position-value heat-zone grid over the cloth before balls/lines
        val heatZone = activeRunoutPlan?.nextBallHeatZone
        if (!isDraggingBall && showHeatZone && heatZone != null) {
            drawHeatZoneOverlay(
                heatZone = heatZone,
                visibility = activeVisibility,
                table = table,
                playLeft = playLeft,
                playTop = playTop,
                playWidth = playWidth,
                playHeight = playHeight,
                pxPerMeter = pxPerMeter,
                toCanvasOffset = ::toCanvasOffset,
            )
        }

        // 2c. Cushion strips: straight, ending at the jaw tips of each pocket
        drawTableCushions(table, geom, tablePalette)

        // 3. Rail diamond sights (8 divisions along length, 4 along width) on the wood beside the cushions
        val diamondRadius = max(2f, railPaddingPx * 0.07f)
        val diamondOffsetPx = geom.diamondOffsetPx()
        val topRailSightY = playTop - diamondOffsetPx
        val bottomRailSightY = playTop + playHeight + diamondOffsetPx
        for (i in listOf(1, 2, 3, 5, 6, 7)) {
            val sightX = playLeft + playWidth * (i / 8f)
            drawCircle(color = DiamondSightColor, radius = diamondRadius, center = Offset(sightX, topRailSightY))
            drawCircle(color = DiamondSightColor, radius = diamondRadius, center = Offset(sightX, bottomRailSightY))
        }
        val leftRailSightX = playLeft - diamondOffsetPx
        val rightRailSightX = playLeft + playWidth + diamondOffsetPx
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

        // 5. Pocket highlights: a stroke across the mouth (jaw tip to jaw tip) and the rim of the hole
        val targetPocketId = if (!isDraggingBall) (focusedStep?.pocketId ?: selectedShot?.pocket?.id) else null
        val selectablePocketsSet = if (!isDraggingBall) availablePockets.toSet() else emptySet()
        for (pocket in table.pockets) {
            val isTargetPocket = pocket.id == targetPocketId
            val isSelectablePocket = pocket.id in selectablePocketsSet
            if (!isTargetPocket && !isSelectablePocket) continue
            val (mouthA, mouthB) = geom.mouthSegment(pocket)
            val (holeCenterPx, holeRadiusPx) = geom.holeCircle(pocket)
            val highlight = if (isTargetPocket) PotLineColor else PotLineColor.copy(alpha = 0.6f)
            val strokeWidth = if (isTargetPocket) max(2.4f, pxPerMeter * 0.0065f) else max(1.6f, pxPerMeter * 0.0045f)
            drawCircle(
                color = highlight.copy(alpha = if (isTargetPocket) 0.30f else 0.14f),
                radius = holeRadiusPx,
                center = holeCenterPx,
            )
            drawCircle(
                color = highlight,
                radius = holeRadiusPx,
                center = holeCenterPx,
                style = Stroke(width = strokeWidth),
            )
            drawLine(
                color = highlight,
                start = mouthA,
                end = mouthB,
                strokeWidth = strokeWidth * 1.3f,
                cap = StrokeCap.Round,
            )
        }

        // 6. Strategy plan / selected shot overlays (suppressed while actively dragging for 60fps feedback)
        val ballRadiusPx = (table.ballRadiusMeters * pxPerMeter).toFloat()
        if (!isDraggingBall) {
            if (activeRunoutPlan != null && planSteps.isNotEmpty()) {
                drawRunoutPlanOverlay(
                    layout = layout,
                    plan = activeRunoutPlan,
                    ballSet = ballSet,
                    focusedStepIndex = clampedStepIdx,
                    nextVisibility = activeVisibility,
                    showLandingCloud = showLandingCloud,
                    ballRadiusPx = ballRadiusPx,
                    pxPerMeter = pxPerMeter,
                    playLeft = playLeft,
                    playTop = playTop,
                    playWidth = playWidth,
                    playHeight = playHeight,
                    textMeasurer = textMeasurer,
                    toCanvasOffset = ::toCanvasOffset,
                )
            } else if (selectedShot != null) {
                drawSelectedShotOverlay(
                    shot = selectedShot,
                    table = table,
                    ballSet = ballSet,
                    visibleSpins = visibleSpins,
                    ballRadiusMeters = table.ballRadiusMeters,
                    pxPerMeter = pxPerMeter,
                    toCanvasOffset = ::toCanvasOffset,
                )
            } else if (fallbackAdvice != null) {
                drawFallbackAdvice(
                    advice = fallbackAdvice,
                    ballSet = ballSet,
                    ballRadiusPx = ballRadiusPx,
                    pxPerMeter = pxPerMeter,
                    toCanvasOffset = ::toCanvasOffset,
                )
            }
        }

        // 7. Balls on the playing surface
        if (!isDraggingBall && pathWaypoints.isNotEmpty()) {
            drawPathWaypointMarkers(
                waypoints = pathWaypoints,
                selectedIndex = activeWaypointIndex,
                ballRadiusPx = ballRadiusPx,
                toCanvasOffset = ::toCanvasOffset,
            )
        }
        val fontSizePx = (ballRadiusPx * 0.80f).coerceAtLeast(6f)
        val fontSizeSp = with(density) { fontSizePx.toSp() }
        val targetBallNumber = if (!isDraggingBall) {
            focusedStep?.targetBall?.number ?: selectedShot?.targetBall?.number
        } else {
            null
        }
        for (ball in layout.balls) {
            val isTargetBall = ball.number == targetBallNumber
            val isCueSelected = highlightCueBall && ball.isCueBall
            drawPoolBall(
                ball = ball,
                ballColor = ballBaseColor(ball.number, ballSet),
                center = toCanvasOffset(ball.position),
                radiusPx = (ball.radiusMeters * pxPerMeter).toFloat(),
                isTargetBall = isTargetBall,
                isCueSelected = isCueSelected,
                textMeasurer = textMeasurer,
                fontSizeSp = fontSizeSp,
                fontSizePx = fontSizePx,
            )
        }

        // 8. Problem-ball dashed warning halos (drawn over balls so warnings remain crisp)
        if (!isDraggingBall && activeRunoutPlan != null && planSteps.isNotEmpty() && activeRunoutPlan.problemBalls.isNotEmpty()) {
            drawProblemBallHalos(
                layout = layout,
                problemBalls = activeRunoutPlan.problemBalls,
                ballRadiusPx = ballRadiusPx,
                playLeft = playLeft,
                playTop = playTop,
                playWidth = playWidth,
                playHeight = playHeight,
                toCanvasOffset = ::toCanvasOffset,
            )
        }
            }

            fun DrawScope.renderZoomedTableScene() {
                if (zoomScale > 1.001f || panOffset != Offset.Zero) {
                    val pivotCenter = Offset(size.width * 0.5f, size.height * 0.5f)
                    withTransform({
                        translate(left = panOffset.x, top = panOffset.y)
                        scale(scaleX = zoomScale, scaleY = zoomScale, pivot = pivotCenter)
                    }) {
                        renderTableLayer()
                    }
                } else {
                    renderTableLayer()
                }
            }

            renderZoomedTableScene()

            // 9. Floating precision magnifying glass loupe while dragging a ball on the table
            val dragNum = activeDraggedBallNumber
            if (dragNum != null) {
                val draggedBall = layout.balls.firstOrNull { it.number == dragNum }
                val focusPos = draggedBall?.let {
                    baseToScreenCanvasOffset(toCanvasOffset(it.position), size, zoomScale, panOffset)
                } ?: activeDragCanvasPos
                if (focusPos != null) {
                    val loupeR = minOf(with(density) { 56.dp.toPx() }, size.minDimension * 0.24f)
                    val clearance = with(density) { 42.dp.toPx() }
                    val loupeCenter = computeLoupeCenter(
                        targetCanvasPos = focusPos,
                        canvasSize = size,
                        loupeRadiusPx = loupeR,
                        fingerClearancePx = clearance,
                    )
                    val badge = if (dragNum == 0) "Cue Ball" else "Ball #$dragNum"
                    val badgeFontSp = with(density) { 10.dp.toSp() }
                    drawPrecisionLoupe(
                        focusCanvasPos = focusPos,
                        loupeCenter = loupeCenter,
                        loupeRadiusPx = loupeR,
                        zoom = 2.4f,
                        accentColor = RunoutColors.PrimaryCyan,
                        badgeLabel = badge,
                        showCenterRedDot = true,
                        showCrosshair = true,
                        textMeasurer = textMeasurer,
                        labelFontSp = badgeFontSp,
                        drawZoomedScene = { renderZoomedTableScene() },
                    )
                }
            }
        }

        // Pocket-free wood-rail zoom controls (aligned at the 25% head-string rail between pockets)
        TableZoomControlsPill(
            zoomScale = zoomScale,
            onZoomOut = { applyStepZoom((zoomScale - 0.5f).coerceAtLeast(MIN_TABLE_ZOOM)) },
            onResetZoom = { applyStepZoom(1.0f) },
            onZoomIn = { applyStepZoom((zoomScale + 0.5f).coerceAtMost(MAX_TABLE_ZOOM)) },
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = -0.50f, verticalBias = 0.98f))
                .padding(bottom = 2.dp),
        )

        // Path inspector entry chip (top rail) when waypoints exist but none is open. The outer box is the
        // 48 dp touch target; the visible pill stays small so it does not cover the felt.
        if (pathWaypoints.isNotEmpty() && activeWaypointIndex == null) {
            Box(
                modifier = Modifier
                    .align(BiasAlignment(horizontalBias = -0.50f, verticalBias = -0.98f))
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button) {
                        val firstTappable = pathWaypoints.indexOfFirst { it.kind != PathWaypointKind.CUE_START }
                        waypointSelection = PathWaypointSelection(
                            pathWaypoints,
                            if (firstTappable >= 0) firstTappable else 0,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC0B1218),
                    border = BorderStroke(1.dp, RunoutColors.PrimaryCyan.copy(alpha = 0.55f)),
                ) {
                    Text(
                        text = "ⓘ Path physics",
                        modifier = Modifier.padding(horizontal = RunoutSpacing.Element, vertical = RunoutSpacing.Micro),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = RunoutColors.PrimaryCyan,
                    )
                }
            }
        }

        val tooltipIdx = activeWaypointIndex
        if (tooltipIdx != null && lastCanvasSize.width > 1f && lastCanvasSize.height > 1f) {
            val wp = pathWaypoints[tooltipIdx]
            val geomNow = computeTableCanvasGeometry(lastCanvasSize.width, lastCanvasSize.height, table)
            val anchor = baseToScreenCanvasOffset(
                geomNow.toCanvasOffset(wp.position),
                lastCanvasSize,
                zoomScale,
                panOffset,
            )
            PathWaypointCallout(
                card = describePathWaypoint(wp, displayMode, pathWaypoints.firstOrNull()),
                accent = pathWaypointColor(wp.kind),
                index = tooltipIdx,
                count = pathWaypoints.size,
                anchor = anchor,
                otherAnchors = pathWaypoints.mapIndexedNotNull { i, other ->
                    if (i == tooltipIdx || other.kind == PathWaypointKind.CUE_START) {
                        null
                    } else {
                        baseToScreenCanvasOffset(
                            geomNow.toCanvasOffset(other.position),
                            lastCanvasSize,
                            zoomScale,
                            panOffset,
                        )
                    }
                },
                ringRadiusPx = markerRingRadiusPx((table.ballRadiusMeters * geomNow.pxPerMeter).toFloat()) * zoomScale,
                onCardPlaced = { calloutCardRect[0] = it },
                onPrev = {
                    waypointSelection = PathWaypointSelection(pathWaypoints, (tooltipIdx - 1).coerceAtLeast(0))
                },
                onNext = {
                    waypointSelection = PathWaypointSelection(
                        pathWaypoints,
                        (tooltipIdx + 1).coerceAtMost(pathWaypoints.lastIndex),
                    )
                },
                onClose = { waypointSelection = null },
            )
        }
    }
}

/** Smallest and largest touch radius, in dp, that grabs a ball (grows with the ball on a zoomed table). */
private const val BALL_HIT_MIN_RADIUS_DP: Float = 24f
private const val BALL_HIT_MAX_RADIUS_DP: Float = 48f

/** Smallest and largest touch radius, in dp, for a tap on the cue ball. */
private const val CUE_BALL_HIT_MIN_RADIUS_DP: Float = 28f
private const val CUE_BALL_HIT_MAX_RADIUS_DP: Float = 52f

/** Radius, in dp, within which a tap selects a path waypoint. */
internal const val PATH_WAYPOINT_TAP_RADIUS_DP: Float = 24f

/** Waypoint choice that belongs to one specific waypoint list; it is ignored once the list is replaced. */
private class PathWaypointSelection(val waypoints: List<PathWaypoint>, val index: Int)

/**
 * Picks the waypoint nearest to [tap] among [screenPoints] (null entries are not tappable) within [radiusPx].
 * Returns the index and the distance in pixels, or `null` when nothing is in reach.
 */
internal fun pickPathWaypointIndex(
    screenPoints: List<Offset?>,
    tap: Offset,
    radiusPx: Float,
): Pair<Int, Float>? {
    var best: Pair<Int, Float>? = null
    for ((i, p) in screenPoints.withIndex()) {
        if (p == null) continue
        val d = hypot(tap.x - p.x, tap.y - p.y)
        if (d <= radiusPx && (best == null || d < best.second)) best = i to d
    }
    return best
}

private fun pathWaypointColor(kind: PathWaypointKind): Color = when (kind) {
    PathWaypointKind.CUE_START -> Color(0xFFF8FAFC)
    PathWaypointKind.SPIN_TRANSITION,
    PathWaypointKind.POST_IMPACT_TRANSITION,
    -> Color(0xFF22D3EE)
    PathWaypointKind.OBJECT_CONTACT -> Color(0xFFF472B6)
    PathWaypointKind.CUSHION_CONTACT -> Color(0xFFFBBF24)
    PathWaypointKind.BALL_CONTACT -> Color(0xFFFB923C)
    PathWaypointKind.OBJECT_BALL_REST,
    PathWaypointKind.CUE_REST,
    PathWaypointKind.CUE_STOPS_SHORT,
    -> Color(0xFFE2E8F0)
    PathWaypointKind.SCRATCH -> ScratchWarningColor
}

/** Radius of a path waypoint marker dot, before zoom. */
private fun markerRadiusPx(ballRadiusPx: Float): Float = max(4.2f, ballRadiusPx * 0.30f)

/** Radius of the highlight ring around the selected marker, before zoom. */
private fun markerRingRadiusPx(ballRadiusPx: Float): Float = markerRadiusPx(ballRadiusPx) * 2.6f

private fun DrawScope.drawPathWaypointMarkers(
    waypoints: List<PathWaypoint>,
    selectedIndex: Int?,
    ballRadiusPx: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val r = markerRadiusPx(ballRadiusPx)
    for ((i, wp) in waypoints.withIndex()) {
        if (wp.kind == PathWaypointKind.CUE_START) continue
        val c = toCanvasOffset(wp.position)
        val color = pathWaypointColor(wp.kind)
        if (i == selectedIndex) {
            drawCircle(color = color.copy(alpha = 0.28f), radius = markerRingRadiusPx(ballRadiusPx), center = c)
            drawCircle(color = color, radius = markerRingRadiusPx(ballRadiusPx), center = c, style = Stroke(width = max(1.6f, r * 0.35f)))
        }
        drawCircle(color = Color(0xE6090D12), radius = r + max(1.4f, r * 0.30f), center = c)
        drawCircle(color = color, radius = r, center = c)
    }
}

/** Title and at most three short lines shown by the path inspector callout. */
internal data class PathWaypointCardText(val title: String, val lines: List<String>)

private fun fmtMps(mps: Double): String {
    val hundredths = (mps * 100.0).roundToInt().coerceAtLeast(0)
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} m/s"
}

private fun fmtCm(meters: Double): String = "${(meters * 100.0).roundToInt().coerceAtLeast(0)} cm"

private fun pctOf(part: Double, whole: Double): Int =
    if (whole <= 1e-9) 0 else ((part / whole) * 100.0).roundToInt().coerceIn(0, 999)

private fun fmtSpeedUnits(units: Double): String {
    val halves = (units * 2.0).roundToInt()
    return if (halves % 2 == 0) "${halves / 2}" else "${halves / 2}.5"
}

private fun ballTag(number: Int?): String = if (number == null) "another ball" else "#$number"

/** Plain-English name for the vertical spin factor `k` (-1 full draw, 0 stun, +1 natural roll). */
private fun spinWords(k: Double): String = when {
    k < -0.08 -> "backspin"
    k <= 0.18 -> "no spin"
    k < 0.92 -> "a little topspin"
    k <= 1.02 -> "natural roll"
    else -> "extra topspin"
}

private fun spinPercentText(k: Double): String = when {
    k < -0.08 -> "${(abs(k) * 100.0).roundToInt()}% backspin"
    k <= 0.18 -> "no spin (stun)"
    k < 0.92 -> "${(k * 100.0).roundToInt()}% of natural roll"
    k <= 1.02 -> "natural roll"
    else -> "${(k * 100.0).roundToInt()}% overspin"
}

private fun englishPercent(sideRatio: Double): Int =
    (abs(sideRatio) / TipOffset.MAX_SAFE_OFFSET_RATIO * 100.0).roundToInt()

private fun englishSide(sideRatio: Double): String = if (sideRatio < 0.0) "left" else "right"

private const val NOTICEABLE_ENGLISH_RATIO = 0.03

/**
 * Words for one path waypoint. [PathWaypoint]s carry numbers only; this turns them into the card text for
 * [mode]: [AppDisplayMode.SIMPLE] is plain English without units, [AppDisplayMode.PRO] adds m/s, %, cm and degrees.
 * [start] is the strike waypoint of the same shot, used for the stroke speed and the starting spin.
 */
internal fun describePathWaypoint(
    wp: PathWaypoint,
    mode: AppDisplayMode,
    start: PathWaypoint?,
): PathWaypointCardText {
    val pro = mode == AppDisplayMode.PRO
    val strokeMps = start?.speedOutMps ?: 0.0
    val k0 = start?.verticalSpinAfter ?: 0.0
    return when (wp.kind) {
        PathWaypointKind.CUE_START -> {
            val side = wp.sideSpinAfter ?: 0.0
            val tipK = wp.verticalSpinAfter ?: 0.0
            val hasEnglish = abs(side) >= NOTICEABLE_ENGLISH_RATIO
            val lines = if (pro) {
                listOf(
                    "Stroke ${fmtMps(wp.speedOutMps ?: 0.0)}" +
                        (wp.speedUnits?.let { ", speed ${fmtSpeedUnits(it)}/10" } ?: "") + ".",
                    "Spin at the tip: ${spinPercentText(tipK)}.",
                    if (hasEnglish) {
                        "English: ${englishPercent(side)}% of max, ${englishSide(side)}."
                    } else {
                        "No side english."
                    },
                )
            } else {
                listOfNotNull(
                    when {
                        tipK < -0.08 -> "Low on the ball: backspin (draw)."
                        tipK <= 0.18 -> "Centre ball: no spin (stun)."
                        tipK < 0.92 -> "Above centre: a little topspin."
                        else -> "High on the ball: topspin (follow)."
                    },
                    if (hasEnglish) "Side english on the ${englishSide(side)}." else null,
                    wp.speedUnits?.let { "Stroke speed ${fmtSpeedUnits(it)} out of 10." },
                )
            }
            PathWaypointCardText("Strike", lines)
        }

        PathWaypointKind.SPIN_TRANSITION -> {
            val atSpeed = wp.speedOutMps ?: 0.0
            val speedLine = "Cue ball at ${fmtMps(atSpeed)} (${pctOf(atSpeed, strokeMps)}% of stroke speed)."
            val after = fmtCm(wp.distanceAlongPathMeters)
            when (wp.transition) {
                SpinPhysics.SpinRegimeChange.DRAW_TO_STUN -> PathWaypointCardText(
                    "Backspin gone",
                    if (pro) {
                        listOf("Draw turns to stun after $after.", speedLine, "Sliding with no spin; roll comes next.")
                    } else {
                        listOf("Backspin has worn off here.", "From this point the ball only slides.")
                    },
                )

                SpinPhysics.SpinRegimeChange.STUN_TO_ROLL -> PathWaypointCardText(
                    if (k0 >= -0.08) "Stun fades" else "Roll begins",
                    if (pro) {
                        listOf(
                            "Slide turns to forward roll after $after.",
                            speedLine,
                            "Spin now ${spinPercentText(wp.verticalSpinAfter ?: 0.0)}, rising.",
                        )
                    } else {
                        listOf("The sliding ball starts to roll forward.", "The cloth is now adding topspin.")
                    },
                )

                else -> PathWaypointCardText(
                    if (k0 > 1.02) "Overspin grips" else "Natural roll",
                    if (pro) {
                        listOf(
                            "Slipping ends after $after: natural roll.",
                            speedLine,
                            if (k0 > 1.02) {
                                "Extra topspin is absorbed by the cloth."
                            } else {
                                "Draw or stun no longer change the line."
                            },
                        )
                    } else if (k0 > 1.02) {
                        listOf("Extra topspin has gripped the cloth.", "From here the ball rolls naturally.")
                    } else if (k0 < -0.08) {
                        listOf("All the backspin is gone.", "From here the ball just rolls forward.")
                    } else {
                        listOf("The ball now rolls without slipping.", "Spin no longer changes its line.")
                    },
                )
            }
        }

        PathWaypointKind.OBJECT_CONTACT -> {
            val ball = ballTag(wp.otherBallNumber)
            val arrive = wp.speedInMps ?: 0.0
            val given = wp.objectBallSpeedMps ?: 0.0
            val transfer = pctOf(given, arrive)
            val keptK = wp.verticalSpinAfter ?: 0.0
            if (pro) {
                val cut = ((wp.cutAngleRadians ?: 0.0) * 180.0 / PI).roundToInt()
                val deflect = ((wp.cueDeflectionRadians ?: 0.0) * 180.0 / PI).roundToInt()
                PathWaypointCardText(
                    "Contact $ball",
                    listOf(
                        "Arrives at ${fmtMps(arrive)}, ${spinPercentText(wp.verticalSpinBefore ?: 0.0)}.",
                        "$cut° cut sends $ball off at ${fmtMps(given)} ($transfer%).",
                        "Leaves at ${fmtMps(wp.speedOutMps ?: 0.0)}, $deflect° off line, spin kept.",
                    ),
                )
            } else {
                PathWaypointCardText(
                    "Contact $ball",
                    listOf(
                        when (wp.regimeBefore) {
                            SpinPhysics.ImpactSpinState.BACKSPIN_DRAW -> "Arrives with the backspin still on."
                            SpinPhysics.ImpactSpinState.STUN_SLIDE -> "Arrives sliding with no spin."
                            SpinPhysics.ImpactSpinState.PARTIAL_FORWARD_ROLL -> "Arrives part-way to rolling."
                            else -> "Arrives rolling forward."
                        },
                        when {
                            transfer >= 80 -> "$ball gets most of the speed."
                            transfer >= 40 -> "$ball gets about half the speed."
                            else -> "$ball gets only a little speed."
                        },
                        if (keptK < -0.08 || keptK > 0.18) {
                            "The cue ball keeps its ${spinWords(keptK)} after contact."
                        } else {
                            "The cue ball leaves without much spin."
                        },
                    ),
                )
            }
        }

        PathWaypointKind.OBJECT_BALL_REST -> {
            val ball = ballTag(wp.otherBallNumber)
            PathWaypointCardText(
                "$ball stops",
                if (pro) {
                    listOf(
                        "$ball rolls ${fmtCm(wp.travelMeters ?: 0.0)} then stops.",
                        "${fmtCm(wp.shortfallMeters ?: 0.0)} from the pocket.",
                        "The cut or stroke gave it too little speed.",
                    )
                } else {
                    listOf("$ball runs out of speed before the pocket.", "Use more speed, or a fuller hit.")
                },
            )
        }

        PathWaypointKind.POST_IMPACT_TRANSITION -> {
            val kAt = wp.verticalSpinBefore ?: 0.0
            PathWaypointCardText(
                when {
                    kAt < -0.08 -> "Draw bites"
                    kAt > 0.18 -> "Follow takes over"
                    else -> "Slide ends"
                },
                if (pro) {
                    listOf(
                        "The slide after contact is over.",
                        "Cue ball now rolls at ${fmtMps(wp.speedOutMps ?: 0.0)}.",
                        "It had ${spinPercentText(kAt)} at contact.",
                    )
                } else {
                    listOf(
                        when {
                            kAt < -0.08 -> "The backspin has pulled the cue ball back."
                            kAt > 0.18 -> "The topspin has carried the cue ball forward."
                            else -> "The slide after the hit has ended."
                        },
                        "From here the cue ball just rolls.",
                    )
                },
            )
        }

        PathWaypointKind.CUSHION_CONTACT -> {
            val vin = wp.speedInMps ?: 0.0
            val vout = wp.speedOutMps ?: 0.0
            val keep = pctOf(vout, vin)
            val before = wp.sideSpinBefore ?: 0.0
            val after = wp.sideSpinAfter ?: 0.0
            val hasEnglish = abs(before) >= NOTICEABLE_ENGLISH_RATIO
            val kAfter = wp.verticalSpinAfter ?: 1.0
            val rollsStraight = kAfter in 0.92..1.02
            PathWaypointCardText(
                "Cushion ${wp.cushionIndex ?: 1}",
                if (pro) {
                    listOf(
                        "Speed ${fmtMps(vin)} → ${fmtMps(vout)} (−${100 - keep}%).",
                        if (hasEnglish) {
                            "English ${englishPercent(before)}% ${englishSide(before)} → ${englishPercent(after)}% ${englishSide(after)} of max."
                        } else {
                            "No english in; leaves with ${englishPercent(after)}% ${englishSide(after)}."
                        },
                        "Leaves with ${spinPercentText(kAfter)}" +
                            if (rollsStraight) ", rolling straight on." else "; the cloth bends it from there.",
                    )
                } else {
                    listOf(
                        "The cue ball keeps about $keep% of its speed.",
                        if (hasEnglish) {
                            "Side english bends the bounce, and the rail changes it."
                        } else {
                            "No side spin going in; the rail adds a little."
                        },
                        if (rollsStraight) {
                            "It rolls straight on from the rail."
                        } else {
                            "It leaves with ${spinWords(kAfter)}, so its path bends after the rail."
                        },
                    )
                },
            )
        }

        PathWaypointKind.BALL_CONTACT -> {
            val ball = ballTag(wp.otherBallNumber)
            val vin = wp.speedInMps ?: 0.0
            val vout = wp.speedOutMps ?: 0.0
            PathWaypointCardText(
                "Hits $ball",
                if (pro) {
                    listOf(
                        "Cue ball meets $ball.",
                        "Speed ${fmtMps(vin)} → ${fmtMps(vout)} (−${100 - pctOf(vout, vin)}%).",
                    )
                } else {
                    listOf("The cue ball bumps $ball on its way.", "It slows down and pushes that ball.")
                },
            )
        }

        PathWaypointKind.CUE_STOPS_SHORT -> PathWaypointCardText(
            "Stops short",
            if (pro) {
                listOf(
                    "Cloth stops the cue ball after ${fmtCm(wp.travelMeters ?: 0.0)}.",
                    "${fmtCm(wp.shortfallMeters ?: 0.0)} before contact with ${ballTag(wp.otherBallNumber)}.",
                    "Add stroke speed.",
                )
            } else {
                listOf(
                    "Not enough speed: the cue ball stops short of ${ballTag(wp.otherBallNumber)}.",
                    "Hit it harder.",
                )
            },
        )

        PathWaypointKind.SCRATCH -> PathWaypointCardText(
            "Scratch!",
            if (pro) {
                listOf(
                    "Cue ball drops in this pocket at ${fmtMps(wp.speedInMps ?: 0.0)}.",
                    "Change the speed or spin.",
                )
            } else {
                listOf("The cue ball still has enough speed to drop in this pocket.", "Change the speed or spin.")
            },
        )

        PathWaypointKind.CUE_REST -> {
            val cushions = wp.cushionCount ?: 0
            val via = if (cushions > 0) " via $cushions cushion${if (cushions > 1) "s" else ""}" else ""
            PathWaypointCardText(
                "Cue ball rests",
                if (pro) {
                    listOf("Rolls ${fmtCm(wp.travelMeters ?: 0.0)} after contact$via.", "Stops here: your next position.")
                } else {
                    listOf("The cue ball stops here$via.", "This is your position for the next shot.")
                },
            )
        }
    }
}

/** Where the path inspector card goes, and the leader line that joins it to its point. */
internal data class CalloutPlacement(
    /** The card's rectangle in canvas pixels. */
    val card: Rect,
    /** Leader line start: the point on the card's edge nearest to the marker. */
    val leaderStart: Offset,
    /** Leader line end: on the marker's ring, outside the card. */
    val leaderEnd: Offset,
)

/** True when [point] lies on the placed card, so the press belongs to the card and not to the table below. */
internal fun isPointOnCallout(card: Rect?, point: Offset): Boolean = card != null && card.contains(point)

private enum class CalloutSide { ABOVE, BELOW, LEFT, RIGHT }

/**
 * Chooses where the path inspector card goes so that it never covers [anchor] (the selected marker).
 *
 * The card keeps [gap] (at least [clearance], which is the marker's ring plus some air) between its edge and the
 * anchor, and stays inside the canvas by [margin]. Sides are tried in the order above, below, left, right:
 * 1. a side with room for the whole card, preferring the one that puts the fewest [others] (the shot's other
 *    waypoints) under the card, ties going to the earlier side;
 * 2. otherwise the card shrinks (its body scrolls) to a height of at least [minHeight] above or below, or a width
 *    of at least [minWidth] left or right, on the side with the most room;
 * 3. as a last resort, on a canvas too small for either, it docks to the canvas edge farthest from the anchor.
 *
 * [measure] returns the card's size for a width and a maximum height (the card is laid out at exactly that width).
 */
internal fun placeCallout(
    canvas: Size,
    anchor: Offset,
    others: List<Offset>,
    ringRadius: Float,
    clearance: Float,
    gap: Float,
    margin: Float,
    maxWidth: Float,
    minWidth: Float,
    minHeight: Float,
    measure: (width: Float, maxHeight: Float) -> Size,
): CalloutPlacement {
    val a = Offset(anchor.x.coerceIn(0f, canvas.width), anchor.y.coerceIn(0f, canvas.height))
    val offset = max(gap, clearance) + 1f
    val availW = (canvas.width - 2f * margin).coerceAtLeast(1f)
    val availH = (canvas.height - 2f * margin).coerceAtLeast(1f)
    val fullW = min(maxWidth, availW)

    fun room(side: CalloutSide): Float = when (side) {
        CalloutSide.ABOVE -> a.y - offset - margin
        CalloutSide.BELOW -> canvas.height - a.y - offset - margin
        CalloutSide.LEFT -> a.x - offset - margin
        CalloutSide.RIGHT -> canvas.width - a.x - offset - margin
    }

    fun rectFor(side: CalloutSide, size: Size): Rect {
        val w = size.width
        val h = size.height
        val centredLeft = (a.x - w / 2f).coerceIn(margin, max(margin, canvas.width - margin - w))
        val centredTop = (a.y - h / 2f).coerceIn(margin, max(margin, canvas.height - margin - h))
        return when (side) {
            CalloutSide.ABOVE -> Rect(centredLeft, a.y - offset - h, centredLeft + w, a.y - offset)
            CalloutSide.BELOW -> Rect(centredLeft, a.y + offset, centredLeft + w, a.y + offset + h)
            CalloutSide.LEFT -> Rect(a.x - offset - w, centredTop, a.x - offset, centredTop + h)
            CalloutSide.RIGHT -> Rect(a.x + offset, centredTop, a.x + offset + w, centredTop + h)
        }
    }

    fun finish(card: Rect): CalloutPlacement {
        val start = Offset(a.x.coerceIn(card.left, card.right), a.y.coerceIn(card.top, card.bottom))
        val dx = start.x - a.x
        val dy = start.y - a.y
        val dist = hypot(dx, dy)
        val end = if (dist > 1e-3f) {
            val reach = min(ringRadius, dist * 0.5f)
            Offset(a.x + dx / dist * reach, a.y + dy / dist * reach)
        } else {
            a
        }
        return CalloutPlacement(card, start, end)
    }

    val sides = CalloutSide.entries

    // 1. A side that holds the whole card.
    val whole = measure(fullW, availH)
    val fitting = sides.filter { side ->
        when (side) {
            CalloutSide.ABOVE, CalloutSide.BELOW -> whole.height <= room(side)
            CalloutSide.LEFT, CalloutSide.RIGHT -> whole.width <= room(side)
        }
    }
    fitting.minByOrNull { side -> rectFor(side, whole).let { r -> others.count { r.contains(it) } } }
        ?.let { return finish(rectFor(it, whole)) }

    // 2. A shrunken card on the side with the most room.
    var best: Pair<CalloutSide, Size>? = null
    var bestRoom = 0f
    for (side in sides) {
        val r = room(side)
        val size = when (side) {
            CalloutSide.ABOVE, CalloutSide.BELOW ->
                if (r >= minHeight) measure(fullW, r).let { Size(it.width, min(it.height, r)) } else null
            CalloutSide.LEFT, CalloutSide.RIGHT ->
                if (r >= minWidth) measure(min(fullW, r), availH).let { Size(min(it.width, r), it.height) } else null
        }
        if (size != null && r > bestRoom) {
            best = side to size
            bestRoom = r
        }
    }
    best?.let { (side, size) -> return finish(rectFor(side, size)) }

    // 3. Dock to the canvas edge farthest from the anchor.
    val w = whole.width
    val h = whole.height
    val left = (a.x - w / 2f).coerceIn(margin, max(margin, canvas.width - margin - w))
    val top = (a.y - h / 2f).coerceIn(margin, max(margin, canvas.height - margin - h))
    val farthest = listOf(
        CalloutSide.ABOVE to a.y,
        CalloutSide.BELOW to canvas.height - a.y,
        CalloutSide.LEFT to a.x,
        CalloutSide.RIGHT to canvas.width - a.x,
    ).maxByOrNull { it.second }?.first ?: CalloutSide.ABOVE
    return finish(
        when (farthest) {
            CalloutSide.ABOVE -> Rect(left, margin, left + w, margin + h)
            CalloutSide.BELOW -> Rect(left, canvas.height - margin - h, left + w, canvas.height - margin)
            CalloutSide.LEFT -> Rect(margin, top, margin + w, top + h)
            CalloutSide.RIGHT -> Rect(canvas.width - margin - w, top, canvas.width - margin, top + h)
        },
    )
}

private const val CALLOUT_MAX_WIDTH_DP: Float = 280f
private const val CALLOUT_MIN_WIDTH_DP: Float = 144f
private const val CALLOUT_MIN_HEIGHT_DP: Float = 128f
private const val CALLOUT_GAP_DP: Float = 24f
private const val CALLOUT_CLEARANCE_DP: Float = 12f

/**
 * Path inspector callout: a card with a title, up to three short lines and Back / Next / Close buttons that each
 * have a 48 dp touch target. It is placed by [placeCallout] so it never covers [anchor], and a leader line joins
 * it to the marker's ring.
 */
@Composable
private fun PathWaypointCallout(
    card: PathWaypointCardText,
    accent: Color,
    index: Int,
    count: Int,
    anchor: Offset,
    otherAnchors: List<Offset>,
    ringRadiusPx: Float,
    onCardPlaced: (Rect?) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    // Written while laying out and read while drawing, so the line always matches the card's actual position.
    var placement by remember { mutableStateOf<CalloutPlacement?>(null) }
    val lastPlacement = remember { arrayOfNulls<CalloutPlacement>(1) }
    val latestOnCardPlaced by rememberUpdatedState(onCardPlaced)
    DisposableEffect(Unit) { onDispose { latestOnCardPlaced(null) } }
    val halo = RunoutColors.Background.copy(alpha = 0.9f)
    Layout(
        content = {
            Surface(
                // Takes every press that starts on the card, so nothing underneath reacts to it; the buttons and
                // the scrolling body inside still get their own events first.
                modifier = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
                shape = RoundedCornerShape(RunoutSpacing.Card),
                color = colors.surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.85f)),
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(start = RunoutSpacing.Card, end = RunoutSpacing.Element),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(RunoutSpacing.Element)
                                .background(accent, RoundedCornerShape(50)),
                        )
                        Text(
                            text = card.title,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = RunoutSpacing.Element),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CalloutButton(label = "Close", enabled = true, onClick = onClose)
                    }
                    // The lines take the height they need; a very tall card scrolls here, so the title row
                    // and the navigation row stay in reach.
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Micro),
                    ) {
                        for (line in card.lines.take(3)) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Micro),
                    ) {
                        CalloutButton(
                            label = "‹ Back",
                            enabled = index > 0,
                            onClick = onPrev,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${index + 1} / $count",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurfaceVariant,
                        )
                        CalloutButton(
                            label = "Next ›",
                            enabled = index < count - 1,
                            onClick = onNext,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        // Drawn behind the card and above the table, which is a sibling underneath.
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = placement ?: return@drawBehind
                val haloWidth = 4.dp.toPx()
                val lineWidth = 2.dp.toPx()
                drawLine(halo, p.leaderStart, p.leaderEnd, strokeWidth = haloWidth, cap = StrokeCap.Round)
                drawLine(accent, p.leaderStart, p.leaderEnd, strokeWidth = lineWidth, cap = StrokeCap.Round)
                drawCircle(halo, radius = 4.5.dp.toPx(), center = p.leaderStart)
                drawCircle(accent, radius = 3.dp.toPx(), center = p.leaderStart)
            },
    ) { measurables, constraints ->
        val fullW = constraints.maxWidth
        val fullH = constraints.maxHeight
        val measurable = measurables.first()
        val result = placeCallout(
            canvas = Size(fullW.toFloat(), fullH.toFloat()),
            anchor = anchor,
            others = otherAnchors,
            ringRadius = ringRadiusPx,
            clearance = ringRadiusPx + CALLOUT_CLEARANCE_DP.dp.toPx(),
            gap = CALLOUT_GAP_DP.dp.toPx(),
            margin = RunoutSpacing.Element.toPx(),
            maxWidth = CALLOUT_MAX_WIDTH_DP.dp.toPx(),
            minWidth = CALLOUT_MIN_WIDTH_DP.dp.toPx(),
            minHeight = CALLOUT_MIN_HEIGHT_DP.dp.toPx(),
        ) { width, maxHeight ->
            val w = width.toInt().coerceAtLeast(1)
            val p = measurable.measure(
                Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = maxHeight.toInt().coerceAtLeast(1)),
            )
            Size(p.width.toFloat(), p.height.toFloat())
        }
        // Measure once more at exactly the chosen size, so the placed card is the one the rectangle describes.
        val chosenW = result.card.width.toInt().coerceAtLeast(1)
        val placeable = measurable.measure(
            Constraints(
                minWidth = chosenW,
                maxWidth = chosenW,
                minHeight = 0,
                maxHeight = result.card.height.toInt().coerceAtLeast(1),
            ),
        )
        if (lastPlacement[0] != result) {
            lastPlacement[0] = result
            latestOnCardPlaced(result.card)
            placement = result
        }
        layout(fullW, fullH) {
            placeable.place(result.card.left.roundToInt(), result.card.top.roundToInt())
        }
    }
}

/** Text button with a 48 dp touch target around a smaller visible pill. */
@Composable
private fun CalloutButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = RunoutSpacing.MinTouchTarget, minHeight = RunoutSpacing.MinTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(RunoutSpacing.Element),
            color = if (enabled) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = RunoutSpacing.Element, vertical = RunoutSpacing.Micro),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun TableZoomControlsPill(
    zoomScale: Float,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isZoomed = zoomScale > 1.02f
    val zoomTenths = (zoomScale * 10f).toInt()
    val zoomLabel = "${zoomTenths / 10}.${zoomTenths % 10}x"

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        // See-through so the rail and anything under the pill stay visible; a faint tint keeps the label readable.
        color = Color(0x1F0B1120),
        border = BorderStroke(
            width = 1.dp,
            color = if (isZoomed) RunoutColors.PrimaryCyan.copy(alpha = 0.45f) else Color(0x33475569),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 28.dp, minHeight = 24.dp)
                    .semantics { contentDescription = "Zoom out" }
                    .clickable(enabled = isZoomed, role = Role.Button, onClick = onZoomOut),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "−",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isZoomed) RunoutColors.TextPrimary else RunoutColors.TextSecondary.copy(alpha = 0.45f),
                )
            }
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 36.dp, minHeight = 24.dp)
                    .background(
                        color = if (isZoomed) RunoutColors.PrimaryCyan.copy(alpha = 0.12f) else Color.Transparent,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .semantics { contentDescription = "Reset zoom" }
                    .clickable(enabled = isZoomed, role = Role.Button, onClick = onResetZoom)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = zoomLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isZoomed) RunoutColors.PrimaryCyan else RunoutColors.TextSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 28.dp, minHeight = 24.dp)
                    .semantics { contentDescription = "Zoom in" }
                    .clickable(enabled = zoomScale < MAX_TABLE_ZOOM - 0.02f, role = Role.Button, onClick = onZoomIn),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (zoomScale < MAX_TABLE_ZOOM - 0.02f) {
                        RunoutColors.TextPrimary
                    } else {
                        RunoutColors.TextSecondary.copy(alpha = 0.45f)
                    },
                )
            }
            if (onToggleFullscreen != null) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 28.dp, minHeight = 24.dp)
                        .semantics {
                            contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen"
                        }
                        .clickable(role = Role.Button, onClick = onToggleFullscreen),
                    contentAlignment = Alignment.Center,
                ) {
                    RunoutVectorIcon(
                        icon = if (isFullscreen) RunoutIcon.EXIT_FULLSCREEN else RunoutIcon.FULLSCREEN,
                        tint = RunoutColors.TextPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/** The curve to draw: the sampled path when the engine supplies one, else the contact vertices. */
private fun TrajectoryPath.drawPoints(): List<Vec2> = sampledPoints.ifEmpty { points }

/**
 * Draws a crisp solid trajectory polyline in the moving ball's own solid base colour
 * (`#0` cue ball = bright white, `#1..#15` solids and stripes = `ballBaseColor`), backed by
 * a thin contrasting casing so green (`#6`), black (`#8`), and white (`#0`) paths stay clear on felt.
 */
private fun DrawScope.drawBallSolidTrajectory(
    points: List<Vec2>,
    ballNumber: Int,
    ballSet: BallSetProfile,
    strokeWidth: Float,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    showEndStopRing: Boolean,
    hitsScratch: Boolean = false,
    alpha: Float = 1.0f,
    toCanvasOffset: (Vec2) -> Offset,
) {
    if (points.isEmpty()) return
    val rawColor = if (ballNumber == 0) {
        Color(0xFFF8FAFC)
    } else {
        ballBaseColor(ballNumber, ballSet)
    }
    val lineColor = rawColor.copy(alpha = alpha)
    val isBlackBall = ballSet.colorForBall(ballNumber) == BallPaletteColor.BLACK && ballNumber != 0
    val casingColor = if (isBlackBall) {
        Color(0xCCE2E8F0).copy(alpha = alpha * 0.85f)
    } else {
        Color(0xA8090D12).copy(alpha = alpha * 0.80f)
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
            color = casingColor,
            style = Stroke(
                width = strokeWidth + max(1.6f, pxPerMeter * 0.0045f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
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
    if (hitsScratch) {
        val crossRadius = ballRadiusPx * 0.65f
        drawCircle(
            color = ScratchWarningColor.copy(alpha = 0.30f * alpha),
            radius = crossRadius * 1.25f,
            center = endPoint,
        )
        drawLine(
            color = ScratchWarningColor.copy(alpha = alpha),
            start = endPoint + Offset(-crossRadius, -crossRadius),
            end = endPoint + Offset(crossRadius, crossRadius),
            strokeWidth = max(2.2f, strokeWidth * 0.85f),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ScratchWarningColor.copy(alpha = alpha),
            start = endPoint + Offset(-crossRadius, crossRadius),
            end = endPoint + Offset(crossRadius, -crossRadius),
            strokeWidth = max(2.2f, strokeWidth * 0.85f),
            cap = StrokeCap.Round,
        )
    } else if (showEndStopRing) {
        drawCircle(
            color = casingColor,
            radius = ballRadiusPx * 0.85f,
            center = endPoint,
            style = Stroke(width = max(2.8f, pxPerMeter * 0.0085f)),
        )
        drawCircle(
            color = rawColor.copy(alpha = 0.24f * alpha),
            radius = ballRadiusPx * 0.85f,
            center = endPoint,
        )
        drawCircle(
            color = lineColor,
            radius = ballRadiusPx * 0.85f,
            center = endPoint,
            style = Stroke(width = max(1.9f, pxPerMeter * 0.0058f)),
        )
        drawCircle(
            color = lineColor,
            radius = max(2.8f, ballRadiusPx * 0.32f),
            center = endPoint,
        )
    }
}

/**
 * The tip that gives this shot the spin state at the object ball that [ShotRecommendation.recommendedSpin] names, for
 * its distance and speed on [table], or `null` when no tip within the miscue limit does.
 */
fun ShotRecommendation.solvedTip(table: TableSpec): TipOffset? = SpinPhysics.tipForImpactState(
    preset = recommendedSpin,
    cueSpeedMps = recommendedCueSpeedMps,
    cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
    table = table,
)

/** A faint continuation of a pot line from where the ball crosses the mouth to the middle of the hole. */
private fun DrawScope.drawIntoPocketLine(
    from: Vec2,
    pocket: PocketSpec,
    color: Color,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
    alpha: Float = 0.45f,
) {
    drawLine(
        color = color.copy(alpha = alpha),
        start = toCanvasOffset(from),
        end = toCanvasOffset(pocket.holeCenter),
        strokeWidth = max(2f, pxPerMeter * 0.0065f),
        cap = StrokeCap.Round,
    )
}

/**
 * Lines of a [FallbackAdvice]: the cue ball's path (through the cushion point on a kick), a ghost ball where it meets
 * the first ball, and each object ball's leg in that ball's colour. A kick's cushion point gets a ring.
 */
private fun DrawScope.drawFallbackAdvice(
    advice: FallbackAdvice,
    ballSet: BallSetProfile,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val strokeWidth = max(2.8f, pxPerMeter * 0.0088f)
    val cueLegs = advice.lines.filter { it.role == FallbackLineRole.CUE_BALL }
    if (cueLegs.isNotEmpty()) {
        drawBallSolidTrajectory(
            points = listOf(cueLegs.first().from) + cueLegs.map { it.to },
            ballNumber = 0,
            ballSet = ballSet,
            strokeWidth = strokeWidth,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            showEndStopRing = advice.cueBallRest != null,
            toCanvasOffset = toCanvasOffset,
        )
        // The ghost ball where the cue ball meets the first ball (the end of the first leg, or of the kick's second).
        val contact = if (advice.cushionPoint != null) cueLegs.last().to else cueLegs.first().to
        drawGhostBallOutline(
            center = toCanvasOffset(contact),
            ballRadiusPx = ballRadiusPx,
            strokeColor = GhostBallStrokeColor,
            pxPerMeter = pxPerMeter,
        )
    }
    for (line in advice.lines) {
        val ballNumber = when (line.role) {
            FallbackLineRole.CUE_BALL -> continue
            FallbackLineRole.FIRST_OBJECT_BALL -> advice.targetBallNumbers.first()
            FallbackLineRole.SECOND_OBJECT_BALL -> advice.targetBallNumbers.getOrNull(1) ?: continue
        }
        drawBallSolidTrajectory(
            points = listOf(line.from, line.to),
            ballNumber = ballNumber,
            ballSet = ballSet,
            strokeWidth = strokeWidth,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            showEndStopRing = false,
            toCanvasOffset = toCanvasOffset,
        )
    }
    advice.cushionPoint?.let { point ->
        drawCircle(
            color = GhostBallStrokeColor,
            radius = ballRadiusPx * 0.45f,
            center = toCanvasOffset(point),
            style = Stroke(width = max(2f, strokeWidth * 0.8f)),
        )
    }
}

private fun DrawScope.drawSelectedShotOverlay(
    shot: ShotRecommendation,
    table: TableSpec,
    ballSet: BallSetProfile,
    visibleSpins: Set<CueBallSpinPreset>,
    ballRadiusMeters: Double,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val aim = shot.aim
    val ghostCenter = toCanvasOffset(aim.ghostBallCenter)
    val ballRadiusPx = (ballRadiusMeters * pxPerMeter).toFloat()
    val preImpact = SpinPhysics.computePreImpactCueState(
        cueSpeedMps = shot.recommendedCueSpeedMps,
        // The preset names the state at the object ball; use the tip that produces it, else the nearest fixed tip.
        tip = shot.solvedTip(table) ?: TipOffset.fromPreset(shot.recommendedSpin),
        cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
        table = table,
    )

    if (preImpact.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT) {
        val stopMeters = aim.cueBallPos + aim.aimDirection * preImpact.travelledDistanceMeters
        drawGhostBallOutline(
            center = ghostCenter,
            ballRadiusPx = ballRadiusPx,
            strokeColor = GhostBallStrokeColor.copy(alpha = 0.45f),
            pxPerMeter = pxPerMeter,
        )
        drawBallSolidTrajectory(
            points = listOf(aim.cueBallPos, stopMeters),
            ballNumber = 0,
            ballSet = ballSet,
            strokeWidth = max(2.8f, pxPerMeter * 0.0088f),
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            showEndStopRing = true,
            toCanvasOffset = toCanvasOffset,
        )
        return
    }

    val vOb0 = MotionPhysics.transferredObjectBallInitialSpeedMps(
        cueImpactSpeedMps = preImpact.impactSpeedMps,
        cutAngleRad = aim.cutAngleRadians,
    )
    val obMaxDist = MotionPhysics.objectBallStoppingDistanceMeters(
        initialObjectSpeedMps = vOb0,
        muS = table.cloth.slidingFrictionMuS,
        muR = table.cloth.rollingResistanceMuR,
    )
    // The object ball drops once its centre reaches the capture line, the same line the engine tests.
    val dropDist = (
        PocketGeometry.distanceToCaptureLine(
            from = aim.objectBallPos,
            direction = aim.objectBallDirection,
            pocket = shot.pocket,
            ballRadius = ballRadiusMeters,
        ) ?: aim.objectToPocketDistanceMeters
        ).coerceAtLeast(0.02)
    val targetStoppedShort = obMaxDist < dropDist
    val targetEndMeters = if (targetStoppedShort) {
        val rawStop = aim.objectBallPos + aim.objectBallDirection * obMaxDist
        Vec2(
            x = rawStop.x.coerceIn(ballRadiusMeters, (table.lengthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)),
            y = rawStop.y.coerceIn(ballRadiusMeters, (table.widthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)),
        )
    } else {
        aim.pocketAimPos
    }

    // Target object ball pot/roll line in its own solid ball colour
    drawBallSolidTrajectory(
        points = listOf(aim.objectBallPos, targetEndMeters),
        ballNumber = shot.targetBall.number,
        ballSet = ballSet,
        strokeWidth = max(2.8f, pxPerMeter * 0.0088f),
        ballRadiusPx = ballRadiusPx,
        pxPerMeter = pxPerMeter,
        showEndStopRing = targetStoppedShort,
        toCanvasOffset = toCanvasOffset,
    )
    if (!targetStoppedShort) {
        drawIntoPocketLine(
            from = aim.pocketAimPos,
            pocket = shot.pocket,
            color = ballBaseColor(shot.targetBall.number, ballSet),
            pxPerMeter = pxPerMeter,
            toCanvasOffset = toCanvasOffset,
        )
    }

    // Pre-contact cue-ball path in solid white
    drawBallSolidTrajectory(
        points = listOf(aim.cueBallPos, aim.ghostBallCenter),
        ballNumber = 0,
        ballSet = ballSet,
        strokeWidth = max(2.4f, pxPerMeter * 0.0076f),
        ballRadiusPx = ballRadiusPx,
        pxPerMeter = pxPerMeter,
        showEndStopRing = false,
        toCanvasOffset = toCanvasOffset,
    )

    // Post-impact cue-ball trajectory polylines: recommended path uses solid white; optional reference presets stay subtle
    val orderedSpins = visibleSpins.sortedBy { it == shot.recommendedSpin }
    for (spin in orderedSpins) {
        val trajectory = shot.cueBallPaths[spin] ?: continue
        val isRecommended = spin == shot.recommendedSpin
        if (isRecommended) {
            drawBallSolidTrajectory(
                points = trajectory.drawPoints(),
                ballNumber = 0,
                ballSet = ballSet,
                strokeWidth = max(3.0f, pxPerMeter * 0.0095f),
                ballRadiusPx = ballRadiusPx,
                pxPerMeter = pxPerMeter,
                showEndStopRing = true,
                hitsScratch = trajectory.hitsScratchPocket != null,
                toCanvasOffset = toCanvasOffset,
            )
        } else {
            drawSpinTrajectory(
                trajectory = trajectory,
                isRecommended = false,
                ballRadiusPx = ballRadiusPx,
                pxPerMeter = pxPerMeter,
                toCanvasOffset = toCanvasOffset,
            )
        }
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
    val points = trajectory.drawPoints()
    if (points.isEmpty()) return

    val baseColor = spinColor(trajectory.spin)
    val lineColor = if (isRecommended) baseColor else baseColor.copy(alpha = 0.62f)
    val strokeWidth = if (isRecommended) {
        max(3.2f, pxPerMeter * 0.010f)
    } else {
        max(1.7f, pxPerMeter * 0.0052f)
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
    isCueSelected: Boolean = false,
    textMeasurer: TextMeasurer,
    fontSizeSp: androidx.compose.ui.unit.TextUnit,
    fontSizePx: Float = radiusPx * 0.80f,
) {
    if (isCueSelected) {
        drawCircle(
            color = Color(0x4438BDF8),
            radius = radiusPx * 1.56f,
            center = center,
        )
        drawCircle(
            color = Color(0xFF38BDF8),
            radius = radiusPx * 1.38f,
            center = center,
            style = Stroke(width = max(2f, radiusPx * 0.16f)),
        )
    }

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

    // Number circle and optically centered label for object balls (1..15)
    if (!ball.isCueBall) {
        val numberPlateRadius = radiusPx * 0.52f
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
                y = centeredDigitTopY(center.y, textLayout, fontSizePx),
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

private fun resolveStepNextVisibility(
    layout: TableLayout,
    plan: RunoutPlan,
    activeIdx: Int,
): PocketVisibilityRegion? {
    val steps = plan.steps
    val primaryStep = steps.getOrNull(activeIdx) ?: return null
    val postShotLayout = primaryStep.resultingLayout ?: TableLayout(
        table = layout.table,
        balls = layout.balls.filter { it.number != primaryStep.targetBall.number },
    )
    val cueRadius = layout.table.ballRadiusMeters
    val nextStep = steps.getOrNull(activeIdx + 1)
    if (nextStep != null) {
        val nextTarget = postShotLayout.ballByNumber(nextStep.targetBall.number) ?: nextStep.targetBall
        val nextPocket = postShotLayout.table.pockets.firstOrNull { it.id == nextStep.pocketId } ?: return null
        return HeatZoneGenerator.computePocketVisibility(
            layout = postShotLayout,
            targetBall = nextTarget,
            pocket = nextPocket,
            cueRadius = cueRadius,
        )
    }

    val fallbackTargetNum = plan.nextBallHeatZone?.targetBallNumbers?.firstOrNull() ?: return null
    val fallbackTarget = postShotLayout.ballByNumber(fallbackTargetNum) ?: return null
    val regions = postShotLayout.table.pockets.map { pocket ->
        HeatZoneGenerator.computePocketVisibility(
            layout = postShotLayout,
            targetBall = fallbackTarget,
            pocket = pocket,
            cueRadius = cueRadius,
        )
    }
    return regions.firstOrNull {
        !HeatZoneGenerator.isPointOccludedForPocket(primaryStep.uncertainty.nominalLandingPosition, it)
    } ?: regions.firstOrNull { it.isPocketClear } ?: regions.firstOrNull()
}

private fun buildOcclusionCutoutPath(
    visibility: PocketVisibilityRegion,
    table: TableSpec,
    toCanvasOffset: (Vec2) -> Offset,
    pxPerMeter: Float,
): Path {
    var combined: Path? = null
    fun addUnion(shape: Path) {
        combined = if (combined == null) {
            shape
        } else {
            Path.combine(PathOperation.Union, combined!!, shape)
        }
    }

    val farDist = max(table.lengthMeters, table.widthMeters) * 3.0

    // 1. Spreading angular shadow cones + exclusion disks for every blocking ball
    for (wedge in visibility.shadowWedges) {
        val blockerDisk = Path().apply {
            val c = toCanvasOffset(wedge.blockerPosition)
            val rPx = (wedge.exclusionRadiusMeters * pxPerMeter).toFloat()
            addOval(Rect(c.x - rPx, c.y - rPx, c.x + rPx, c.y + rPx))
        }
        addUnion(blockerDisk)

        val conePath = Path().apply {
            val lt = toCanvasOffset(wedge.leftTangentMeters)
            val rt = toCanvasOffset(wedge.rightTangentMeters)
            moveTo(lt.x, lt.y)
            val steps = 5
            val startAngle = wedge.centerAngleRadians - wedge.halfAngleRadians
            val endAngle = wedge.centerAngleRadians + wedge.halfAngleRadians
            for (i in 0..steps) {
                val frac = i.toDouble() / steps.toDouble()
                val a = startAngle + (endAngle - startAngle) * frac
                val pt = toCanvasOffset(wedge.originMeters + Vec2(cos(a), sin(a)) * farDist)
                lineTo(pt.x, pt.y)
            }
            lineTo(rt.x, rt.y)
            close()
        }
        addUnion(conePath)
    }

    // 2. Target ball exclusion disk
    val targetDisk = Path().apply {
        val tc = toCanvasOffset(visibility.targetBallPosition)
        val tr = (visibility.targetExclusionRadiusMeters * pxPerMeter).toFloat()
        addOval(Rect(tc.x - tr, tc.y - tr, tc.x + tr, tc.y + tr))
    }
    addUnion(targetDisk)

    // 3. Impossible back-cut region on the pocket side of the ghost ball (|cut| > MAX_HEAT_CUT_ANGLE_RAD)
    val objDir = visibility.objectToPocketDirection
    val pocketAngle = atan2(objDir.y, objDir.x)
    val invalidHalfSweep = PI - HeatZoneGenerator.MAX_HEAT_CUT_ANGLE_RAD
    val backCutSector = Path().apply {
        val gOffset = toCanvasOffset(visibility.ghostBallCenter)
        moveTo(gOffset.x, gOffset.y)
        val segs = 6
        val aStart = pocketAngle - invalidHalfSweep
        val aEnd = pocketAngle + invalidHalfSweep
        for (i in 0..segs) {
            val frac = i.toDouble() / segs.toDouble()
            val a = aStart + (aEnd - aStart) * frac
            val rim = toCanvasOffset(visibility.ghostBallCenter + Vec2(cos(a), sin(a)) * farDist)
            lineTo(rim.x, rim.y)
        }
        close()
    }
    addUnion(backCutSector)

    return combined ?: Path()
}

private fun DrawScope.drawHeatZoneOverlay(
    heatZone: HeatZoneMap,
    visibility: PocketVisibilityRegion? = null,
    table: TableSpec,
    playLeft: Float,
    playTop: Float,
    playWidth: Float,
    playHeight: Float,
    pxPerMeter: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    if (visibility != null && !visibility.isPocketClear) return

    val safeCols = heatZone.cols.coerceAtLeast(1)
    val safeRows = heatZone.rows.coerceAtLeast(1)
    val cellWidthPx = playWidth / safeCols.toFloat()
    val cellHeightPx = playHeight / safeRows.toFloat()
    val maxRight = playLeft + playWidth
    val maxBottom = playTop + playHeight

    fun drawCells() {
        for (cell in heatZone.cells) {
            val cellColor = when {
                cell.value >= 0.62 -> HeatZoneHighColor
                cell.value >= 0.32 -> HeatZoneMediumColor
                cell.value >= 0.06 -> HeatZoneLowColor
                else -> continue
            }
            val cellLeft = (playLeft + cell.col * cellWidthPx).coerceIn(playLeft, maxRight)
            val cellTop = (playTop + cell.row * cellHeightPx).coerceIn(playTop, maxBottom)
            val cellRight = (cellLeft + cellWidthPx).coerceAtMost(maxRight)
            val cellBottom = (cellTop + cellHeightPx).coerceAtMost(maxBottom)
            val drawW = (cellRight - cellLeft).coerceAtLeast(0f)
            val drawH = (cellBottom - cellTop).coerceAtLeast(0f)
            if (drawW <= 0f || drawH <= 0f) continue
            drawRect(
                color = cellColor,
                topLeft = Offset(x = cellLeft, y = cellTop),
                size = Size(drawW, drawH),
            )
        }
    }

    clipRect(
        left = playLeft,
        top = playTop,
        right = maxRight,
        bottom = maxBottom,
    ) {
        if (visibility != null) {
            val cutout = buildOcclusionCutoutPath(
                visibility = visibility,
                table = table,
                toCanvasOffset = toCanvasOffset,
                pxPerMeter = pxPerMeter,
            )
            clipPath(path = cutout, clipOp = ClipOp.Difference) {
                drawCells()
            }
        } else {
            drawCells()
        }
    }
}

private fun DrawScope.drawRunoutPlanOverlay(
    layout: TableLayout,
    plan: RunoutPlan,
    ballSet: BallSetProfile,
    focusedStepIndex: Int? = null,
    nextVisibility: PocketVisibilityRegion? = null,
    showLandingCloud: Boolean,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    playLeft: Float,
    playTop: Float,
    playWidth: Float,
    playHeight: Float,
    textMeasurer: TextMeasurer,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val steps = plan.steps
    if (steps.isEmpty()) return

    val activeIdx = focusedStepIndex?.coerceIn(0, steps.lastIndex) ?: 0
    val primaryStep = steps[activeIdx]
    val nextStep = steps.getOrNull(activeIdx + 1)
    val shotTightness = computeShotTightness(primaryStep, nextStep)

    val cuePos = layout.cueBall()?.position
    val ghostMeters = primaryStep.compensatedAim.compensatedGhostBallCenter
    val firstGhostCenter = toCanvasOffset(ghostMeters)
    val postSim = primaryStep.uncertainty.postShotSimulation
    val cueStoppedBeforeContact = postSim?.cueBallStoppedBeforeImpact == true

    if (cueStoppedBeforeContact) {
        drawGhostBallOutline(
            center = firstGhostCenter,
            ballRadiusPx = ballRadiusPx,
            strokeColor = GhostBallStrokeColor.copy(alpha = 0.45f),
            pxPerMeter = pxPerMeter,
        )
        val shortCueTraj = postSim!!.cueBallTrajectory
        drawBallSolidTrajectory(
            points = shortCueTraj.drawPoints(),
            ballNumber = 0,
            ballSet = ballSet,
            strokeWidth = max(3.1f, pxPerMeter * 0.0096f),
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            showEndStopRing = true,
            hitsScratch = shortCueTraj.hitsScratchPocket != null,
            toCanvasOffset = toCanvasOffset,
        )
    } else {
        // 1. Active step target object-ball trajectory in its own solid ball colour (stopping short if underpowered)
        val simulatedTargetPoints = postSim?.targetBallTrajectory?.let { it.sampledPoints.ifEmpty { it.points } }
            ?.takeIf { it.size >= 2 }
        val targetPoints = simulatedTargetPoints
            ?: listOf(primaryStep.targetBall.position, primaryStep.pocketCenter)
        val targetStoppedShort = postSim != null && !postSim.targetBallPotted
        val stepPocketSpec = layout.table.pockets.firstOrNull { it.id == primaryStep.pocketId }
        drawBallSolidTrajectory(
            points = targetPoints,
            ballNumber = primaryStep.targetBall.number,
            ballSet = ballSet,
            strokeWidth = max(2.9f, pxPerMeter * 0.0090f),
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            showEndStopRing = targetStoppedShort,
            toCanvasOffset = toCanvasOffset,
        )
        // A simulated path that drops already ends at the hole; a straight pot line ends at the aim point.
        if (simulatedTargetPoints == null && !targetStoppedShort && stepPocketSpec != null) {
            drawIntoPocketLine(
                from = primaryStep.pocketCenter,
                pocket = stepPocketSpec,
                color = ballBaseColor(primaryStep.targetBall.number, ballSet),
                pxPerMeter = pxPerMeter,
                toCanvasOffset = toCanvasOffset,
            )
        }

        // 2. Active step cue-ball pre-contact path in solid white
        if (cuePos != null) {
            drawBallSolidTrajectory(
                points = listOf(cuePos, ghostMeters),
                ballNumber = 0,
                ballSet = ballSet,
                strokeWidth = max(2.4f, pxPerMeter * 0.0076f),
                ballRadiusPx = ballRadiusPx,
                pxPerMeter = pxPerMeter,
                showEndStopRing = false,
                toCanvasOffset = toCanvasOffset,
            )
        }

        drawGhostBallOutline(
            center = firstGhostCenter,
            ballRadiusPx = ballRadiusPx,
            strokeColor = GhostBallStrokeColor,
            pxPerMeter = pxPerMeter,
        )

        // 3. Active step post-impact cue-ball trajectory in solid white
        val nomCueTraj = primaryStep.uncertainty.nominalTrajectory
        drawBallSolidTrajectory(
            points = nomCueTraj.drawPoints(),
            ballNumber = 0,
            ballSet = ballSet,
            strokeWidth = max(3.1f, pxPerMeter * 0.0096f),
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            showEndStopRing = true,
            hitsScratch = nomCueTraj.hitsScratchPocket != null,
            toCanvasOffset = toCanvasOffset,
        )
    }

    // 4. Active step 3-level position heat zone & landing cloud (strictly clipped inside playable felt and carved by blocking balls)
    if (showLandingCloud) {
        drawLandingCloudOverlay(
            uncertainty = primaryStep.uncertainty,
            tightness = shotTightness,
            visibility = nextVisibility,
            table = layout.table,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            playLeft = playLeft,
            playTop = playTop,
            playWidth = playWidth,
            playHeight = playHeight,
            toCanvasOffset = toCanvasOffset,
        )
    }

    // 5. Solid per-ball same-coloured trajectory lines for any secondary balls moved in this shot
    if (primaryStep.disturbedBalls.isNotEmpty()) {
        drawDisturbedBallsOverlay(
            clipOutsetPx = requiredRailBandMeters(layout.table).toFloat() * pxPerMeter,
            disturbedBalls = primaryStep.disturbedBalls,
            ballSet = ballSet,
            ballRadiusPx = ballRadiusPx,
            pxPerMeter = pxPerMeter,
            playLeft = playLeft,
            playTop = playTop,
            playWidth = playWidth,
            playHeight = playHeight,
            alpha = 0.96f,
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
        val prevLanding = steps[idx - 1].resultingLayout?.cueBall()?.position
            ?: steps[idx - 1].uncertainty.nominalLandingPosition
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
        layout.table.pockets.firstOrNull { it.id == step.pocketId }?.let { stepPocketSpec ->
            drawIntoPocketLine(
                from = step.pocketCenter,
                pocket = stepPocketSpec,
                color = stepColor,
                pxPerMeter = pxPerMeter,
                toCanvasOffset = toCanvasOffset,
                alpha = 0.40f,
            )
        }

        if (step.disturbedBalls.isNotEmpty()) {
            drawDisturbedBallsOverlay(
                clipOutsetPx = requiredRailBandMeters(layout.table).toFloat() * pxPerMeter,
                disturbedBalls = step.disturbedBalls,
                ballSet = ballSet,
                ballRadiusPx = ballRadiusPx * 0.9f,
                pxPerMeter = pxPerMeter,
                playLeft = playLeft,
                playTop = playTop,
                playWidth = playWidth,
                playHeight = playHeight,
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
    clipOutsetPx: Float,
    disturbedBalls: List<DisplacedBallPath>,
    ballSet: BallSetProfile,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    playLeft: Float,
    playTop: Float,
    playWidth: Float,
    playHeight: Float,
    alpha: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val strokeW = max(2.5f, pxPerMeter * 0.0078f)

    clipRect(
        left = playLeft - clipOutsetPx,
        top = playTop - clipOutsetPx,
        right = playLeft + playWidth + clipOutsetPx,
        bottom = playTop + playHeight + clipOutsetPx,
    ) {
        for (path in disturbedBalls) {
            if (path.points.size < 2) continue
            drawBallSolidTrajectory(
                points = path.sampledPoints.ifEmpty { path.points },
                ballNumber = path.ballNumber,
                ballSet = ballSet,
                strokeWidth = strokeW,
                ballRadiusPx = ballRadiusPx,
                pxPerMeter = pxPerMeter,
                showEndStopRing = path.pocketedIn == null,
                hitsScratch = false,
                alpha = alpha,
                toCanvasOffset = toCanvasOffset,
            )
        }
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
    val badgeFontSp = 9.sp
    val labelLayout = textMeasurer.measure(
        text = step.stepNumber.toString(),
        style = TextStyle(
            color = Color(0xFF082F49),
            fontSize = badgeFontSp,
            fontWeight = FontWeight.ExtraBold,
        ),
    )
    drawText(
        textLayoutResult = labelLayout,
        topLeft = Offset(
            x = badgeCenter.x - labelLayout.size.width * 0.5f,
            y = centeredDigitTopY(badgeCenter.y, labelLayout, badgeFontSp.toPx()),
        ),
    )
}

private fun DrawScope.drawLandingCloudOverlay(
    uncertainty: ShotUncertaintyResult,
    tightness: Float,
    visibility: PocketVisibilityRegion? = null,
    table: TableSpec,
    ballRadiusPx: Float,
    pxPerMeter: Float,
    playLeft: Float,
    playTop: Float,
    playWidth: Float,
    playHeight: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val nominalPos = uncertainty.nominalLandingPosition
    val meanPos = uncertainty.meanLandingPosition
    val zoneCenter = toCanvasOffset(nominalPos)
    val meanCenter = toCanvasOffset(meanPos)
    val skewOffset = nominalPos.distanceTo(meanPos)
    val effectiveRadiusMeters = max(
        uncertainty.landingSpreadRadiusMeters,
        skewOffset + table.ballRadiusMeters,
    )
    val outerRadiusPx = (effectiveRadiusMeters * pxPerMeter)
        .toFloat()
        .coerceAtLeast(ballRadiusPx * 0.90f)
    val clampedTightness = tightness.coerceIn(0f, 1f)
    val amberFraction = 0.84f - 0.28f * clampedTightness
    val greenFraction = 0.58f - 0.32f * clampedTightness
    val midRadiusPx = (outerRadiusPx * amberFraction).coerceAtLeast(ballRadiusPx * 0.55f)
    val innerRadiusPx = (outerRadiusPx * greenFraction).coerceAtLeast(ballRadiusPx * 0.32f)

    val ringDash = max(5f, ballRadiusPx * 0.45f)
    val ringGap = max(4f, ballRadiusPx * 0.30f)

    fun drawThreeLevelZones() {
        // Level 3 Outer Heat Zone: outer 95% landing spread (tightness-scaled boundary)
        drawCircle(
            color = Color(0x2CF87171),
            radius = outerRadiusPx,
            center = zoneCenter,
        )
        drawCircle(
            color = Color(0xAAF87171),
            radius = outerRadiusPx,
            center = zoneCenter,
            style = Stroke(
                width = max(1.4f, pxPerMeter * 0.0044f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(ringDash, ringGap), 0f),
            ),
        )

        // Level 2 Middle Heat Zone: playable tolerance band
        drawCircle(
            color = Color(0x36FBBF24),
            radius = midRadiusPx,
            center = zoneCenter,
        )
        drawCircle(
            color = Color(0xAAFBBF24),
            radius = midRadiusPx,
            center = zoneCenter,
            style = Stroke(width = max(1.3f, pxPerMeter * 0.0040f)),
        )

        // Level 1 Inner Heat Zone: ideal sweet spot (wider on easy cuts, tighter on demanding shots)
        drawCircle(
            color = Color(0x464ADE80),
            radius = innerRadiusPx,
            center = zoneCenter,
        )
        drawCircle(
            color = Color(0xCC4ADE80),
            radius = innerRadiusPx,
            center = zoneCenter,
            style = Stroke(width = max(1.6f, pxPerMeter * 0.0048f)),
        )
    }

    clipRect(
        left = playLeft,
        top = playTop,
        right = playLeft + playWidth,
        bottom = playTop + playHeight,
    ) {
        if (visibility == null) {
            drawThreeLevelZones()
        } else if (!visibility.isPocketClear) {
            // Goal pocket itself is blocked: show only warning spread boundary (no green/amber leave zone)
            drawCircle(
                color = Color(0x24F87171),
                radius = outerRadiusPx,
                center = zoneCenter,
            )
            drawCircle(
                color = Color(0xAAF87171),
                radius = outerRadiusPx,
                center = zoneCenter,
                style = Stroke(
                    width = max(1.4f, pxPerMeter * 0.0044f),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(ringDash, ringGap), 0f),
                ),
            )
        } else {
            val cutout = buildOcclusionCutoutPath(
                visibility = visibility,
                table = table,
                toCanvasOffset = toCanvasOffset,
                pxPerMeter = pxPerMeter,
            )
            clipPath(path = cutout, clipOp = ClipOp.Difference) {
                drawThreeLevelZones()
            }

            // Show subtle shadow-cone boundary rays spreading past blocking balls intersecting the landing zone
            val landingDisk = Path().apply {
                addOval(
                    Rect(
                        zoneCenter.x - outerRadiusPx,
                        zoneCenter.y - outerRadiusPx,
                        zoneCenter.x + outerRadiusPx,
                        zoneCenter.y + outerRadiusPx,
                    ),
                )
            }
            val rayDash = PathEffect.dashPathEffect(floatArrayOf(ringDash * 0.85f, ringGap * 0.85f), 0f)
            clipPath(landingDisk) {
                for (wedge in visibility.shadowWedges) {
                    val lt = toCanvasOffset(wedge.leftTangentMeters)
                    val lf = toCanvasOffset(wedge.leftFarMeters)
                    val rt = toCanvasOffset(wedge.rightTangentMeters)
                    val rf = toCanvasOffset(wedge.rightFarMeters)
                    drawLine(
                        color = Color(0x6EF87171),
                        start = lt,
                        end = lf,
                        strokeWidth = max(1.1f, pxPerMeter * 0.0032f),
                        pathEffect = rayDash,
                    )
                    drawLine(
                        color = Color(0x6EF87171),
                        start = rt,
                        end = rf,
                        strokeWidth = max(1.1f, pxPerMeter * 0.0032f),
                        pathEffect = rayDash,
                    )
                }
            }
        }

        // Individual sigma-point landing cloud dots (warning coral if snookered/occluded)
        val dotRadius = max(2.2f, ballRadiusPx * 0.17f)
        for (pt in uncertainty.landingCloud) {
            val ptOffset = toCanvasOffset(pt)
            val isOccluded = visibility != null && HeatZoneGenerator.isPointOccludedForPocket(pt, visibility)
            drawCircle(
                color = if (isOccluded) Color(0xCC7F1D1D) else Color(0xAA0C4A6E),
                radius = dotRadius + 0.8f,
                center = ptOffset,
            )
            drawCircle(
                color = if (isOccluded) Color(0xFFF87171) else LandingCloudDotColor,
                radius = dotRadius,
                center = ptOffset,
            )
        }

        // Secondary skewed-mean marker when ensemble mean differs noticeably from nominal stop
        if (skewOffset > 0.02) {
            drawCircle(
                color = Color(0x9938BDF8),
                radius = max(2.6f, ballRadiusPx * 0.22f),
                center = meanCenter,
                style = Stroke(
                    width = max(1.2f, pxPerMeter * 0.0035f),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                ),
            )
        }

        // Nominal landing center marker
        val nominalOccluded = visibility != null &&
            HeatZoneGenerator.isPointOccludedForPocket(nominalPos, visibility)
        drawCircle(
            color = if (nominalOccluded) Color(0xFFF87171) else Color(0xFF38BDF8),
            radius = max(3f, ballRadiusPx * 0.25f),
            center = zoneCenter,
            style = Stroke(width = max(1.5f, pxPerMeter * 0.0045f)),
        )
    }
}

private fun DrawScope.drawProblemBallHalos(
    layout: TableLayout,
    problemBalls: List<ProblemBallReport>,
    ballRadiusPx: Float,
    playLeft: Float,
    playTop: Float,
    playWidth: Float,
    playHeight: Float,
    toCanvasOffset: (Vec2) -> Offset,
) {
    val dashLen = max(4f, ballRadiusPx * 0.48f)
    val gapLen = max(3f, ballRadiusPx * 0.32f)
    val haloEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), 0f)
    val haloRadius = ballRadiusPx * 1.48f

    clipRect(
        left = playLeft,
        top = playTop,
        right = playLeft + playWidth,
        bottom = playTop + playHeight,
    ) {
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
    defaultTipOffset: TipOffset? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val latestTip by rememberUpdatedState(tipOffset)
    val latestOnUpdateTip by rememberUpdatedState(onUpdateTip)
    val density = LocalDensity.current
    var isDraggingTip by remember { mutableStateOf(false) }

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
            .defaultMinSize(minWidth = 104.dp, minHeight = 104.dp)
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    updateFromPointer(offset, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        isDraggingTip = true
                        updateFromPointer(startOffset, size.width.toFloat(), size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        updateFromPointer(change.position, size.width.toFloat(), size.height.toFloat())
                    },
                    onDragEnd = { isDraggingTip = false },
                    onDragCancel = { isDraggingTip = false },
                )
            },
    ) {
        val ballRadiusPx = min(size.width, size.height) * 0.43f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val safeRadiusPx = ballRadiusPx * TipOffset.MAX_SAFE_OFFSET_RATIO.toFloat()
        val contactCenter = Offset(
            x = center.x + (tipOffset.clampedSideRatio.toFloat() * ballRadiusPx),
            y = center.y - (tipOffset.clampedVerticalRatio.toFloat() * ballRadiusPx),
        )

        fun DrawScope.renderCueBallFace() {
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

            // Planner-default contact ring marker when the user overrides tip position in Sandbox
            if (defaultTipOffset != null) {
                val diff = hypot(
                    defaultTipOffset.clampedSideRatio - tipOffset.clampedSideRatio,
                    defaultTipOffset.clampedVerticalRatio - tipOffset.clampedVerticalRatio,
                )
                if (diff > 0.04) {
                    val defCenter = Offset(
                        x = center.x + (defaultTipOffset.clampedSideRatio.toFloat() * ballRadiusPx),
                        y = center.y - (defaultTipOffset.clampedVerticalRatio.toFloat() * ballRadiusPx),
                    )
                    val defR = max(5f, ballRadiusPx * 0.13f)
                    drawCircle(
                        color = Color(0x384ADE80),
                        radius = defR * 1.45f,
                        center = defCenter,
                    )
                    drawCircle(
                        color = Color(0xFF4ADE80),
                        radius = defR,
                        center = defCenter,
                        style = Stroke(width = max(1.8f, ballRadiusPx * 0.032f)),
                    )
                }
            }

            // Red/blue cue-tip contact dot at (clampedSideRatio, clampedVerticalRatio)
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

        renderCueBallFace()

        if (isDraggingTip) {
            val loupeR = minOf(with(density) { 34.dp.toPx() }, size.minDimension * 0.29f)
            val clearance = with(density) { 22.dp.toPx() }
            val loupeCenter = computeLoupeCenter(
                targetCanvasPos = contactCenter,
                canvasSize = size,
                loupeRadiusPx = loupeR,
                fingerClearancePx = clearance,
                preferOppositeCorner = true,
            )
            drawPrecisionLoupe(
                focusCanvasPos = contactCenter,
                loupeCenter = loupeCenter,
                loupeRadiusPx = loupeR,
                zoom = 2.2f,
                accentColor = RunoutColors.PrimaryCyan,
                badgeLabel = "Spin",
                showCenterRedDot = false,
                showCrosshair = true,
                textMeasurer = textMeasurer,
                labelFontSp = 8.sp,
                drawZoomedScene = { renderCueBallFace() },
            )
        }
    }
}

/**
 * Computes normalized shot tightness in `0f..1f` (`0f` = forgiving / wide pocket & leave,
 * `1f` = razor-tight cut, delicate speed/spin, or narrow position window).
 */
fun computeShotTightness(
    step: PlannedShotStep,
    nextStep: PlannedShotStep? = null,
): Float {
    val cutDeg = abs(step.compensatedAim.geometricAim.cutAngleRadians) * (180.0 / PI)
    val cutTightness = (cutDeg / 65.0).coerceIn(0.0, 1.0)
    val makeTightness = ((0.96 - step.uncertainty.makeProbability) / 0.65).coerceIn(0.0, 1.0)
    val scratchTightness = (step.uncertainty.scratchProbability / 0.25).coerceIn(0.0, 1.0)
    val spinTightness = (step.recommendedTip.magnitudeRatio / 0.50).coerceIn(0.0, 1.0)
    val nextTightness = nextStep?.let { ((0.94 - it.uncertainty.makeProbability) / 0.65).coerceIn(0.0, 1.0) } ?: 0.0
    return (
        0.32 * cutTightness +
            0.30 * makeTightness +
            0.16 * nextTightness +
            0.12 * spinTightness +
            0.10 * scratchTightness
        ).toFloat().coerceIn(0f, 1f)
}

/**
 * Computes normalized shot tightness in `0f..1f` for single-shot recommendations.
 */
fun computeShotTightness(shot: ShotRecommendation): Float {
    val cutDeg = abs(shot.aim.cutAngleRadians) * (180.0 / PI)
    val cutTightness = (cutDeg / 65.0).coerceIn(0.0, 1.0)
    val makeTightness = ((0.96 - shot.makeProbability) / 0.65).coerceIn(0.0, 1.0)
    val spinTightness = if (shot.recommendedSpin == CueBallSpinPreset.DRAW) 0.55 else 0.15
    return (0.45 * cutTightness + 0.40 * makeTightness + 0.15 * spinTightness)
        .toFloat()
        .coerceIn(0f, 1f)
}

/**
 * Plain-English tightness badge corresponding to [tightness].
 */
fun shotTightnessLabel(tightness: Float): String = when {
    tightness < 0.33f -> "Wide"
    tightness < 0.64f -> "Moderate"
    else -> "Tight"
}

/**
 * Compact front-facing cue-ball hit guide showing the recommended tip contact point and a
 * 3-level Green / Amber / Red heat zone clipped inside the ball boundary that tightens on
 * demanding cuts and opens up on forgiving shots.
 */
@Composable
fun CueBallHitGuide(
    tipOffset: TipOffset,
    tightness: Float = 0.35f,
    modifier: Modifier = Modifier,
    showAxisLabels: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val clampedTightness = tightness.coerceIn(0f, 1f)
    val interactionModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Canvas(modifier = interactionModifier.aspectRatio(1f)) {
        val ballRadiusPx = min(size.width, size.height) * 0.45f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val safeRadiusPx = ballRadiusPx * TipOffset.MAX_SAFE_OFFSET_RATIO.toFloat()

        // Drop shadow
        drawCircle(
            color = Color(0x4D000000),
            radius = ballRadiusPx,
            center = center + Offset(ballRadiusPx * 0.06f, ballRadiusPx * 0.08f),
        )

        // Shaded 3D white sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF1F5F9),
                    Color(0xFFCBD5E1),
                ),
                center = center - Offset(ballRadiusPx * 0.25f, ballRadiusPx * 0.28f),
                radius = ballRadiusPx * 1.35f,
            ),
            radius = ballRadiusPx,
            center = center,
        )

        val ballClipPath = Path().apply {
            addOval(
                Rect(
                    left = center.x - ballRadiusPx,
                    top = center.y - ballRadiusPx,
                    right = center.x + ballRadiusPx,
                    bottom = center.y + ballRadiusPx,
                ),
            )
        }

        val contactCenter = Offset(
            x = center.x + (tipOffset.clampedSideRatio.toFloat() * ballRadiusPx),
            y = center.y - (tipOffset.clampedVerticalRatio.toFloat() * ballRadiusPx),
        )

        clipPath(ballClipPath) {
            // 3-level tightness heat zone centered on recommended contact point
            val redRadiusPx = ballRadiusPx * (0.56f - 0.24f * clampedTightness)
            val amberRadiusPx = ballRadiusPx * (0.42f - 0.20f * clampedTightness)
            val greenRadiusPx = ballRadiusPx * (0.28f - 0.16f * clampedTightness)

            drawCircle(
                color = Color(0x36F87171),
                radius = redRadiusPx,
                center = contactCenter,
            )
            drawCircle(
                color = Color(0x4AFBBF24),
                radius = amberRadiusPx,
                center = contactCenter,
            )
            drawCircle(
                color = Color(0x5E4ADE80),
                radius = greenRadiusPx,
                center = contactCenter,
            )
            drawCircle(
                color = Color(0xCC16A34A),
                radius = greenRadiusPx,
                center = contactCenter,
                style = Stroke(width = max(1.2f, ballRadiusPx * 0.032f)),
            )
        }

        // Crosshairs and 0.50R miscue-safe ring
        val crosshairColor = Color(0x66475569)
        val crosshairStroke = max(1.0f, ballRadiusPx * 0.025f)
        drawLine(
            color = crosshairColor,
            start = Offset(center.x - ballRadiusPx * 0.86f, center.y),
            end = Offset(center.x + ballRadiusPx * 0.86f, center.y),
            strokeWidth = crosshairStroke,
        )
        drawLine(
            color = crosshairColor,
            start = Offset(center.x, center.y - ballRadiusPx * 0.86f),
            end = Offset(center.x, center.y + ballRadiusPx * 0.86f),
            strokeWidth = crosshairStroke,
        )

        val dash = max(3f, ballRadiusPx * 0.11f)
        val gap = max(2.5f, ballRadiusPx * 0.09f)
        drawCircle(
            color = Color(0x880284C7),
            radius = safeRadiusPx,
            center = center,
            style = Stroke(
                width = max(1.1f, ballRadiusPx * 0.028f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f),
            ),
        )

        if (showAxisLabels && ballRadiusPx >= 24f) {
            val labelStyle = TextStyle(
                color = Color(0xAA475569),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
            )
            val axisMarkers = listOf(
                "F" to Offset(center.x, center.y - ballRadiusPx * 0.74f),
                "D" to Offset(center.x, center.y + ballRadiusPx * 0.74f),
                "L" to Offset(center.x - ballRadiusPx * 0.74f, center.y),
                "R" to Offset(center.x + ballRadiusPx * 0.74f, center.y),
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
        }

        // Outer ball contour
        drawCircle(
            color = Color(0xCC64748B),
            radius = ballRadiusPx,
            center = center,
            style = Stroke(width = max(1.3f, ballRadiusPx * 0.04f)),
        )

        // Bold cue-tip strike point
        val tipDotRadius = max(3.2f, ballRadiusPx * 0.14f)
        drawCircle(
            color = Color(0xFF0F172A),
            radius = tipDotRadius + max(1.2f, ballRadiusPx * 0.03f),
            center = contactCenter,
        )
        drawCircle(
            color = Color(0xFFEF4444),
            radius = tipDotRadius,
            center = contactCenter,
        )
    }
}
