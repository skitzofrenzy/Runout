package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.app.calibration.CalibrationSessionPreset
import com.runout.app.calibration.PlatformTiltSensorController
import com.runout.app.calibration.SampleCalibrationSessions
import com.runout.engine.calibration.CalibratedTableParams
import com.runout.engine.calibration.CalibrationValidationReport
import com.runout.engine.calibration.SavedTableProfile
import com.runout.engine.calibration.SlopeCell
import com.runout.engine.calibration.TableLevelCalibration
import com.runout.engine.calibration.TableSlopeMap
import com.runout.engine.calibration.TrackedBallTrajectory
import com.runout.engine.calibration.TrackedTrajectoryEvent
import com.runout.engine.calibration.TrajectoryEventType
import com.runout.engine.calibration.VideoFrameBallObservation
import com.runout.engine.math.Vec2
import com.runout.engine.model.ClothParams
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.tan

private const val METERS_TO_MM: Double = 1000.0
private const val METERS_TO_FEET: Double = 3.28084

private val CalSuccessGreen = Color(0xFF4ADE80)
private val CalWarningAmber = Color(0xFFFBBF24)
private val CalAccentCyan = Color(0xFF38BDF8)
private val CalClothBaize = Color(0xFF11643D)
private val CalRailWood = Color(0xFF3E2415)

private val COL_LABELS = listOf("Head", "Center", "Foot")
private val ROW_LABELS = listOf("Left", "Mid", "Right")

/**
 * Interactive Table Calibration & Level screen supporting saved table profile switching,
 * overhead video 5-drill fitting with held-out shot validation, and 0°/180° tilt-sensor
 * plus slow-roll drift inspection.
 */
@Composable
fun CalibrationScreen(
    savedProfiles: List<SavedTableProfile>,
    activeProfileId: String?,
    onSelectOrSaveProfile: (SavedTableProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions = remember { SampleCalibrationSessions.allSessions() }
    val tiltController = remember { PlatformTiltSensorController() }
    val defaultParams = remember { CalibratedTableParams(ClothParams()) }

    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    val safeIndex = selectedPresetIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))
    val selectedPreset = sessions.getOrNull(safeIndex)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CalibrationHeaderBar(
            sensorBackendName = tiltController.sensorBackendName,
            activeProfile = savedProfiles.firstOrNull { it.id == activeProfileId },
        )

        SavedTableProfilesCard(
            savedProfiles = savedProfiles,
            activeProfileId = activeProfileId,
            onApplyProfile = onSelectOrSaveProfile,
        )

        if (selectedPreset != null) {
            val calibrationRun = remember(selectedPreset) {
                SampleCalibrationSessions.runFullSessionCalibration(selectedPreset)
            }
            val calibratedProfile = calibrationRun.first
            val validationReport = calibrationRun.second
            val trackedTrajectory = calibrationRun.third

            val sensorPairedReadings = remember(selectedPreset.id, tiltController) {
                tiltController.readPairedGridPreset(selectedPreset.id)
            }
            val effectiveSlopeMap = remember(selectedPreset, calibratedProfile, sensorPairedReadings) {
                if (sensorPairedReadings.isNotEmpty()) {
                    TableLevelCalibration.buildSlopeMapFromPairedReadings(
                        readings = sensorPairedReadings,
                        table = selectedPreset.baseTableSpec,
                    )
                } else {
                    calibratedProfile.slopeMap
                }
            }

            CalibrationRunnerCard(
                sessions = sessions,
                selectedIndex = safeIndex,
                selectedPreset = selectedPreset,
                calibratedProfile = calibratedProfile,
                validationReport = validationReport,
                trackedTrajectory = trackedTrajectory,
                defaultParams = defaultParams,
                onSelectPreset = { selectedPresetIndex = it },
                onSaveAndActivate = { onSelectOrSaveProfile(calibratedProfile) },
            )

            TableLevelCard(
                sensorBackendName = tiltController.sensorBackendName,
                slopeMap = effectiveSlopeMap,
                slowRollDistanceMeters = selectedPreset.slowRollDistanceMeters,
                slowRollLateralDriftMeters = selectedPreset.slowRollLateralDriftMeters,
                fittedMuR = calibratedProfile.calibratedParams.cloth.rollingResistanceMuR,
            )
        }
    }
}

@Composable
private fun CalibrationHeaderBar(
    sensorBackendName: String,
    activeProfile: SavedTableProfile?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Table Calibration & Level",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Fit cloth speed, sliding friction, cushion rebound, english coupling, and throw from overhead video drills, verify table level via 0°/180° tilt readings, and switch saved table profiles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadgeChip(
                text = sensorBackendName,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            val activeLabel = if (activeProfile != null) {
                "Active: ${activeProfile.profileName}"
            } else {
                "Using default uncalibrated table"
            }
            val activeAccent = if (activeProfile != null) CalSuccessGreen else MaterialTheme.colorScheme.tertiary
            StatusBadgeChip(
                text = activeLabel,
                containerColor = activeAccent.copy(alpha = 0.14f),
                contentColor = activeAccent,
                borderColor = activeAccent.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun SavedTableProfilesCard(
    savedProfiles: List<SavedTableProfile>,
    activeProfileId: String?,
    onApplyProfile: (SavedTableProfile) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Saved Table Profiles (${savedProfiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Multi-table memory",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            savedProfiles.forEach { profile ->
                SavedProfileItemCard(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onApply = { onApplyProfile(profile) },
                )
            }
        }
    }
}

@Composable
private fun SavedProfileItemCard(
    profile: SavedTableProfile,
    isActive: Boolean,
    onApply: () -> Unit,
) {
    val isUnlevel = profile.slopeMap.isNoticeablyUnlevel
    val levelBadgeText = if (isUnlevel) "Unlevel roll-off warning" else "Level"
    val levelBadgeColor = if (isUnlevel) CalWarningAmber else CalSuccessGreen
    val cloth = profile.calibratedParams.cloth
    val spec = profile.baseTableSpec
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = profile.profileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        StatusBadgeChip(
                            text = "Active",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                StatusBadgeChip(
                    text = levelBadgeText,
                    containerColor = levelBadgeColor.copy(alpha = 0.16f),
                    contentColor = levelBadgeColor,
                    borderColor = levelBadgeColor.copy(alpha = 0.45f),
                )
            }

            Text(
                text = "Calibrated ${profile.calibratedDateIso} • ${spec.name}: " +
                    "${formatFixed(spec.lengthMeters, 2)} × ${formatFixed(spec.widthMeters, 2)} m " +
                    "(${formatFixed(spec.lengthMeters * METERS_TO_FEET, 1)} × ${formatFixed(spec.widthMeters * METERS_TO_FEET, 1)} ft)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "μr ${formatFixed(cloth.rollingResistanceMuR, 4)}  •  " +
                    "μs ${formatFixed(cloth.slidingFrictionMuS, 3)}  •  " +
                    "ec ${formatFixed(cloth.cushionRestitutionEc, 3)}  •  " +
                    "μb ${formatFixed(profile.calibratedParams.ballBallFrictionMuB, 3)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val maxSlopeDeg = radiansToDegrees(profile.slopeMap.maxSlopeMagnitudeRad)
            val maxSlopeMmPerM = radToMmPerM(profile.slopeMap.maxSlopeMagnitudeRad)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Max tilt: ${formatFixed(maxSlopeDeg, 2)}° (${formatFixed(maxSlopeMmPerM, 1)} mm/m)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUnlevel) CalWarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isActive) {
                    OutlinedButton(
                        onClick = onApply,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Text("Apply to Table Planner")
                    }
                } else {
                    Button(onClick = onApply) {
                        Text("Apply to Table Planner")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationRunnerCard(
    sessions: List<CalibrationSessionPreset>,
    selectedIndex: Int,
    selectedPreset: CalibrationSessionPreset,
    calibratedProfile: SavedTableProfile,
    validationReport: CalibrationValidationReport,
    trackedTrajectory: TrackedBallTrajectory,
    defaultParams: CalibratedTableParams,
    onSelectPreset: (Int) -> Unit,
    onSaveAndActivate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Overhead Video & 5-Drill Calibration Runner",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Select a recorded calibration session to normalize 100/120 Hz lighting flicker, Kalman-track ball kinematics, fit all 5 physical drills, and verify held-out accuracy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sessions.forEachIndexed { index, preset ->
                    val isSelected = index == selectedIndex
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(index) },
                        label = {
                            Text(
                                text = preset.title,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            Text(
                text = "${selectedPreset.subtitle}  •  Drills: ${selectedPreset.lagTrials.size} lag, " +
                    "${selectedPreset.stunTrials.size} stun, ${selectedPreset.cushionTrials.size} cushion, " +
                    "${selectedPreset.bankSpinTrials.size} bank spin, ${selectedPreset.cutThrowTrials.size} cut throw, " +
                    "${selectedPreset.heldOutShots.size} held-out shots",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VideoTrackingSummarySection(
                table = selectedPreset.baseTableSpec,
                rawFrames = selectedPreset.videoFrames,
                trackedTrajectory = trackedTrajectory,
            )

            FittedParametersComparisonSection(
                fitted = validationReport.fittedParams,
                defaults = defaultParams,
            )

            HeldOutValidationSection(report = validationReport)

            Button(
                onClick = onSaveAndActivate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Text(
                    text = "Save & Activate Calibrated Profile (${calibratedProfile.profileName})",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun VideoTrackingSummarySection(
    table: TableSpec,
    rawFrames: List<VideoFrameBallObservation>,
    trackedTrajectory: TrackedBallTrajectory,
) {
    val rawLumas = rawFrames.map { it.frameMeanLuma }
    val rawMin = rawLumas.minOrNull() ?: 128.0
    val rawMax = rawLumas.maxOrNull() ?: 128.0
    val normSamples = trackedTrajectory.normalizedLumaSamples
    val normMean = if (normSamples.isNotEmpty()) normSamples.average() else 128.0
    val normMin = normSamples.minOrNull() ?: 128.0
    val normMax = normSamples.maxOrNull() ?: 128.0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Video Tracking & Flicker Summary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusBadgeChip(
                    text = "${rawFrames.size} frames",
                    containerColor = CalAccentCyan.copy(alpha = 0.16f),
                    contentColor = CalAccentCyan,
                )
            }

            Text(
                text = "100/120 Hz luma flicker normalization: active • Raw frame luma " +
                    "${formatFixed(rawMin, 1)}–${formatFixed(rawMax, 1)} → normalized mean " +
                    "${formatFixed(normMean, 1)} (${formatFixed(normMin, 1)}–${formatFixed(normMax, 1)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "2D Kalman filter tracked ${trackedTrajectory.points.size} state points [x, y, vx, vy] and detected ${trackedTrajectory.events.size} kinematic events:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OverheadTrajectoryPreviewCanvas(
                table = table,
                rawFrames = rawFrames,
                trackedTrajectory = trackedTrajectory,
                modifier = Modifier.fillMaxWidth(),
            )

            if (trackedTrajectory.events.isEmpty()) {
                Text(
                    text = "No kinematic events detected in this clip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                trackedTrajectory.events.forEach { event ->
                    KinematicEventRow(event = event)
                }
            }
        }
    }
}

@Composable
private fun OverheadTrajectoryPreviewCanvas(
    table: TableSpec,
    rawFrames: List<VideoFrameBallObservation>,
    trackedTrajectory: TrackedBallTrajectory,
    modifier: Modifier = Modifier,
) {
    val aspect = (table.lengthMeters / table.widthMeters).toFloat().coerceIn(1.5f, 2.3f)
    Canvas(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(10.dp))
            .background(CalRailWood)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
    ) {
        val pad = minOf(size.width, size.height) * 0.08f
        val playW = (size.width - 2f * pad).coerceAtLeast(1f)
        val playH = (size.height - 2f * pad).coerceAtLeast(1f)
        val scaleX = playW / table.lengthMeters.toFloat()
        val scaleY = playH / table.widthMeters.toFloat()

        fun toOffset(pos: Vec2): Offset = Offset(
            x = pad + pos.x.toFloat() * scaleX,
            y = pad + pos.y.toFloat() * scaleY,
        )

        drawRect(
            color = CalClothBaize,
            topLeft = Offset(pad, pad),
            size = Size(playW, playH),
        )
        drawRect(
            color = Color(0x55E2E8F0),
            topLeft = Offset(pad, pad),
            size = Size(playW, playH),
            style = Stroke(width = 1.5f),
        )

        // Raw noisy detections
        rawFrames.forEach { frame ->
            drawCircle(
                color = Color(0x66F8FAFC),
                radius = 2.5f,
                center = toOffset(frame.rawPositionMeters),
            )
        }

        // Kalman-filtered path
        val points = trackedTrajectory.points
        if (points.size >= 2) {
            val path = Path().apply {
                val start = toOffset(points.first().positionMeters)
                moveTo(start.x, start.y)
                for (i in 1 until points.size) {
                    val p = toOffset(points[i].positionMeters)
                    lineTo(p.x, p.y)
                }
            }
            drawPath(
                path = path,
                color = CalAccentCyan,
                style = Stroke(
                    width = 3f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        // Kinematic event markers
        trackedTrajectory.events.forEach { event ->
            val center = toOffset(event.positionMeters)
            val markerColor = eventColor(event.type)
            drawCircle(
                color = markerColor.copy(alpha = 0.25f),
                radius = 10f,
                center = center,
            )
            drawCircle(
                color = markerColor,
                radius = 5f,
                center = center,
            )
        }
    }
}

@Composable
private fun KinematicEventRow(event: TrackedTrajectoryEvent) {
    val accent = eventColor(event.type)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${event.type.name} (${formatEventLabel(event.type)})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(
                text = "t = ${formatFixed(event.timestampSeconds, 2)} s • pos ${formatPositionMeters(event.positionMeters)} • " +
                    "speed ${formatFixed(event.speedBeforeMps, 2)} → ${formatFixed(event.speedAfterMps, 2)} m/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FittedParametersComparisonSection(
    fitted: CalibratedTableParams,
    defaults: CalibratedTableParams,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Fitted Parameters vs. Defaults",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Parameter",
                    modifier = Modifier.weight(1.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Fitted",
                    modifier = Modifier.weight(0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Default",
                    modifier = Modifier.weight(0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TableDivider()

            FittedParameterRow(
                label = "Rolling resistance (μr)",
                fittedValue = formatFixed(fitted.cloth.rollingResistanceMuR, 4),
                defaultValue = formatFixed(defaults.cloth.rollingResistanceMuR, 4),
            )
            FittedParameterRow(
                label = "Sliding friction (μs)",
                fittedValue = formatFixed(fitted.cloth.slidingFrictionMuS, 3),
                defaultValue = formatFixed(defaults.cloth.slidingFrictionMuS, 3),
            )
            FittedParameterRow(
                label = "Cushion restitution (ec)",
                fittedValue = formatFixed(fitted.cloth.cushionRestitutionEc, 3),
                defaultValue = formatFixed(defaults.cloth.cushionRestitutionEc, 3),
            )
            FittedParameterRow(
                label = "Cushion friction (f_c)",
                fittedValue = formatFixed(fitted.cloth.cushionFrictionFc, 3),
                defaultValue = formatFixed(defaults.cloth.cushionFrictionFc, 3),
            )
            FittedParameterRow(
                label = "Ball–ball friction (μb)",
                fittedValue = formatFixed(fitted.ballBallFrictionMuB, 3),
                defaultValue = formatFixed(defaults.ballBallFrictionMuB, 3),
            )
        }
    }
}

@Composable
private fun FittedParameterRow(
    label: String,
    fittedValue: String,
    defaultValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = fittedValue,
            modifier = Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = defaultValue,
            modifier = Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeldOutValidationSection(report: CalibrationValidationReport) {
    val calStopMm = report.calibratedStoppingRmsErrorMeters * METERS_TO_MM
    val defStopMm = report.defaultStoppingRmsErrorMeters * METERS_TO_MM
    val stopReductionPct = report.relativeStoppingErrorReductionFraction * 100.0
    val reboundReductionPct = relativeReductionPercent(
        calibrated = report.calibratedReboundSpeedRmsErrorMps,
        baseline = report.defaultReboundSpeedRmsErrorMps,
    )
    val throwReductionPct = relativeReductionPercent(
        calibrated = report.calibratedThrowRmsErrorRad,
        baseline = report.defaultThrowRmsErrorRad,
    )

    val isImproved = report.relativeStoppingErrorReductionFraction > 0.0
    val statusColor = if (isImproved) CalSuccessGreen else CalWarningAmber

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = statusColor.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Held-Out Shot Validation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusBadgeChip(
                    text = "${formatFixed(stopReductionPct, 1)}% error reduction",
                    containerColor = statusColor.copy(alpha = 0.18f),
                    contentColor = statusColor,
                )
            }

            Text(
                text = "Calibrated Stopping RMS: ${formatFixed(calStopMm, 1)} mm vs " +
                    "Default Stopping RMS: ${formatFixed(defStopMm, 1)} mm (${formatFixed(stopReductionPct, 1)}% error reduction)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ValidationMetricTile(
                    title = "Rebound Speed RMS",
                    calibratedText = "${formatFixed(report.calibratedReboundSpeedRmsErrorMps, 3)} m/s",
                    defaultText = "Default ${formatFixed(report.defaultReboundSpeedRmsErrorMps, 3)} m/s",
                    reductionText = "${formatFixed(reboundReductionPct, 1)}% better",
                    modifier = Modifier.weight(1f),
                )
                ValidationMetricTile(
                    title = "Cut-Throw RMS",
                    calibratedText = "${formatFixed(radiansToDegrees(report.calibratedThrowRmsErrorRad), 2)}°",
                    defaultText = "Default ${formatFixed(radiansToDegrees(report.defaultThrowRmsErrorRad), 2)}°",
                    reductionText = "${formatFixed(throwReductionPct, 1)}% better",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ValidationMetricTile(
    title: String,
    calibratedText: String,
    defaultText: String,
    reductionText: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = calibratedText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CalSuccessGreen,
            )
            Text(
                text = "$defaultText ($reductionText)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TableLevelCard(
    sensorBackendName: String,
    slopeMap: TableSlopeMap,
    slowRollDistanceMeters: Double,
    slowRollLateralDriftMeters: Double,
    fittedMuR: Double,
) {
    val fineSlopeRad = remember(slowRollDistanceMeters, slowRollLateralDriftMeters, fittedMuR) {
        TableLevelCalibration.estimateSlopeFromSlowRoll(
            rollDistanceMeters = slowRollDistanceMeters,
            lateralDriftMeters = slowRollLateralDriftMeters,
            muR = fittedMuR,
        )
    }
    val levelAccent = if (slopeMap.isNoticeablyUnlevel) CalWarningAmber else CalSuccessGreen

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (slopeMap.isNoticeablyUnlevel) {
                CalWarningAmber.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Table Level (0° / 180° Tilt Sensor & Slow-Roll Drift)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadgeChip(
                    text = if (slopeMap.isNoticeablyUnlevel) "Unlevel roll-off warning" else "Level",
                    containerColor = levelAccent.copy(alpha = 0.16f),
                    contentColor = levelAccent,
                    borderColor = levelAccent.copy(alpha = 0.45f),
                )
            }

            Text(
                text = "Tilt readings: $sensorBackendName",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = CalAccentCyan,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                Text(
                    text = "These are built-in sample sessions. They illustrate the 0° / 180° rotate-in-place method: a reading θ₀ at each 3 × 3 cell, a second reading θ₁₈₀ with the phone turned 180° in place, and θ = (θ₀ − θ₁₈₀) / 2 so camera-bump tilt and accelerometer zero-bias cancel out.",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "3 × 3 Table Slope Grid (X along length, Y across width)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SlopeGrid3x3(slopeMap = slopeMap)

            // Slow-Roll Fine Drift Check
            val driftMm = slowRollLateralDriftMeters * METERS_TO_MM
            val fineDeg = radiansToDegrees(fineSlopeRad)
            val fineMmPerM = radToMmPerM(fineSlopeRad)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Slow-Roll Fine Drift Check",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Lateral drift: ${formatFixed(driftMm, 1)} mm over " +
                            "${formatFixed(slowRollDistanceMeters, 1)} m -> Fine slope: " +
                            "${formatFixed(fineDeg, 2)} deg (${formatFixed(fineMmPerM, 1)} mm/m)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            abs(fineSlopeRad) >= TableLevelCalibration.UNLEVEL_WARNING_THRESHOLD_RAD -> CalWarningAmber
                            abs(fineSlopeRad) >= 0.0005 -> CalAccentCyan
                            else -> CalSuccessGreen
                        },
                    )
                    Text(
                        text = "Resolves sub-sensor roll-off below the ~0.1° tilt-sensor floor using Δy = (5/7) · L · sin(θ) / μr.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlopeGrid3x3(slopeMap: TableSlopeMap) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until slopeMap.rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (col in 0 until slopeMap.cols) {
                    val cell = slopeMap.cells.firstOrNull { it.col == col && it.row == row }
                    SlopeCellTile(
                        col = col,
                        row = row,
                        cell = cell,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlopeCellTile(
    col: Int,
    row: Int,
    cell: SlopeCell?,
    modifier: Modifier = Modifier,
) {
    val magRad = if (cell != null) hypot(cell.slopeXRad, cell.slopeYRad) else 0.0
    val isCellUnlevel = magRad >= TableLevelCalibration.UNLEVEL_WARNING_THRESHOLD_RAD
    val cellAccent = if (isCellUnlevel) CalWarningAmber else CalSuccessGreen
    val colLabel = COL_LABELS.getOrElse(col) { "C$col" }
    val rowLabel = ROW_LABELS.getOrElse(row) { "R$row" }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = cellAccent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, cellAccent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "$colLabel · $rowLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = cellAccent,
            )
            if (cell != null) {
                Text(
                    text = "X: ${formatSignedFixed(radToMmPerM(cell.slopeXRad), 1)} mm/m (${formatSignedFixed(radiansToDegrees(cell.slopeXRad), 2)}°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Y: ${formatSignedFixed(radToMmPerM(cell.slopeYRad), 1)} mm/m (${formatSignedFixed(radiansToDegrees(cell.slopeYRad), 2)}°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatPositionMeters(cell.centerMeters),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "No sample",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusBadgeChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun TableDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

private fun eventColor(type: TrajectoryEventType): Color = when (type) {
    TrajectoryEventType.SLIDE_TO_ROLL_TRANSITION -> Color(0xFFFBBF24)
    TrajectoryEventType.CUSHION_BOUNCE -> Color(0xFF38BDF8)
    TrajectoryEventType.BALL_STOP -> Color(0xFF4ADE80)
}

private fun formatEventLabel(type: TrajectoryEventType): String = when (type) {
    TrajectoryEventType.SLIDE_TO_ROLL_TRANSITION -> "Slide → Roll"
    TrajectoryEventType.CUSHION_BOUNCE -> "Cushion Bounce"
    TrajectoryEventType.BALL_STOP -> "Ball Stop"
}

private fun relativeReductionPercent(calibrated: Double, baseline: Double): Double {
    if (!calibrated.isFinite() || !baseline.isFinite() || baseline <= 1e-12) return 0.0
    return ((baseline - calibrated) / baseline).coerceIn(-1.0, 1.0) * 100.0
}

private fun radiansToDegrees(rad: Double): Double = rad * 180.0 / PI

private fun radToMmPerM(rad: Double): Double = tan(rad) * METERS_TO_MM

private fun formatPositionMeters(pos: Vec2): String =
    "(${formatFixed(pos.x, 2)}, ${formatFixed(pos.y, 2)}) m"

private fun formatFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(value * scale).toLong()
    val sign = if (scaled < 0L) "-" else ""
    val absScaled = abs(scaled)
    val whole = absScaled / scale
    val frac = absScaled % scale
    return if (decimals == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${frac.toString().padStart(decimals, '0')}"
    }
}

private fun formatSignedFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(value * scale).toLong()
    val sign = if (scaled >= 0L) "+" else "-"
    val absScaled = abs(scaled)
    val whole = absScaled / scale
    val frac = absScaled % scale
    return if (decimals == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${frac.toString().padStart(decimals, '0')}"
    }
}
