package pl.ballistics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import pl.ballistics.app.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.ballistics.app.CalcResult
import pl.ballistics.app.Lead
import pl.ballistics.app.AzimuthRow
import pl.ballistics.app.TargetAnalysis
import pl.ballistics.app.TargetInputs
import pl.ballistics.app.lead
import pl.ballistics.app.data.AmmoProfile
import pl.ballistics.app.data.AngularUnit
import pl.ballistics.app.data.ChartKind
import pl.ballistics.app.data.Conditions
import pl.ballistics.app.data.FocalPlane
import pl.ballistics.app.data.UiSettings
import pl.ballistics.app.toNumber
import pl.ballistics.app.reticle.Mark
import pl.ballistics.app.reticle.Reticle
import pl.ballistics.app.reticle.Reticles
import pl.ballistics.app.reticle.Weight
import pl.ballistics.core.TrajectoryPoint
import pl.ballistics.core.Units
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.roundToInt

/** The shot at [distance]; [point] is null when the bullet does not get there. */
private data class Solved(val distance: Double, val point: TrajectoryPoint?, val lead: Lead?, val analysis: TargetAnalysis?)

/** Where the bullet lands, in reticle units: hold this point on the target. */
private data class Hold(val point: TrajectoryPoint, val x: Double, val y: Double)

@Composable
fun TargetScreen(
    result: CalcResult?,
    conditions: Conditions,
    ui: UiSettings,
    update: ((Conditions) -> Conditions) -> Unit,
    onSaveAmmo: (AmmoProfile) -> Unit,
    onAddAmmoCopy: (from: AmmoProfile, copy: AmmoProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result !is CalcResult.Ok) {
        TableScreen(result, modifier = modifier)
        return
    }
    val weapon = result.weapon
    val own = Reticles.byId(weapon.reticleId)
    var previewId by remember(weapon.id) { mutableStateOf(own.id) }
    // The rifle's own reticle is drawn with its scope's scale step; a previewed one as designed.
    val graduation = weapon.reticleGraduation.toNumber()?.takeIf { previewId == own.id }
    val reticle = remember(previewId, graduation) { Reticles.byId(previewId, graduation) }
    val sfp = weapon.focalPlane == FocalPlane.SFP
    val maxMag = weapon.maxMagnification.toNumber()?.takeIf { it > 1 } ?: 25.0
    val minMag = weapon.minMagnification.toNumber()?.takeIf { it > 0 && it < maxMag } ?: 1.0
    val calMag = weapon.calibratedMagnification.toNumber()?.takeIf { it > 0 } ?: maxMag
    // Start where the reticle reads true and looks as designed: SFP at its calibrated power, FFP at full power.
    val startMag = if (sfp) calMag.coerceIn(minMag, maxMag) else maxMag
    var magnification by remember(weapon.id, sfp, startMag, minMag) { mutableFloatStateOf(startMag.toFloat()) }
    var unlocked by remember(weapon.id) { mutableStateOf(false) }
    // Turrets turned by hand stay where they are, at any distance, until reset to the computed corrections.
    var manualUp by remember(weapon.id) { mutableStateOf<Double?>(null) }
    var manualRight by remember(weapon.id) { mutableStateOf<Double?>(null) }
    // Unlocked, the zoom goes past the scope's range: down to 1× and up to twice its top power.
    val range = if (unlocked) minOf(1.0, minMag).toFloat()..(maxMag * 2).toFloat() else minMag.toFloat()..maxMag.toFloat()
    val currentRange by rememberUpdatedState(range)

    val solution = result.solution
    val points = solution.points.filter { it.rangeM > 0 }
    val maxDistance = points.lastOrNull()?.rangeM ?: 1000.0
    // While the slider is dragged the distance lives here and is saved when the finger lifts.
    var dragged by remember { mutableStateOf<Float?>(null) }
    var typed by remember { mutableStateOf<String?>(null) }
    val distance = dragged?.roundToInt()?.toDouble()
        ?: conditions.targetRangeM.toNumber()?.takeIf { it > 0 }
        ?: points.getOrNull(points.size / 2)?.rangeM ?: 0.0
    fun setDistance(m: Double) = update { it.copy(targetRangeM = m.coerceIn(1.0, maxDistance).roundToInt().toString()) }
    val targetSpeed = conditions.targetSpeedMps.toNumber()?.takeIf { it > 0 && conditions.targetMoving }
    val targetDirection = conditions.targetDirectionDeg.toNumber() ?: 90.0

    // Only the charts of open cards are worked out; opening a card brings its charts in.
    val cardOf = mapOf(
        ChartKind.BRACKET_DIRECTION to stringResource(R.string.section_wind_bracket),
        ChartKind.RANGE_ERROR to stringResource(R.string.section_range_error),
        ChartKind.HIT_RANGE to stringResource(R.string.section_hit_probability),
    )
    val chartsCard = stringResource(R.string.section_charts)
    val computedCharts = ui.charts.filter { OpenCards.isOpen(cardOf[it] ?: chartsCard) }.toSet()
    val inputs = TargetInputs(
        windMinMps = conditions.windMinMps.toNumber()?.takeIf { it >= 0 },
        windMaxMps = conditions.windMaxMps.toNumber()?.takeIf { it >= 0 },
        widthM = (conditions.targetWidthCm.toNumber()?.takeIf { it > 0 } ?: 50.0) / 100,
        heightM = (conditions.targetHeightCm.toNumber()?.takeIf { it > 0 } ?: 50.0) / 100,
        velocitySdMps = result.ammo.velocitySd.toNumber()?.takeIf { it >= 0 } ?: TargetInputs.DEFAULT_VELOCITY_SD,
        windSdMps = conditions.windUncertaintyMps.toNumber()?.takeIf { it >= 0 } ?: 1.0,
        rangeSdM = conditions.rangeUncertaintyM.toNumber()?.takeIf { it >= 0 } ?: 5.0,
        groupRad = (weapon.groupMoa.toNumber()?.takeIf { it > 0 } ?: TargetInputs.DEFAULT_GROUP_MOA) * Units.MOA,
        charts = computedCharts,
        chartsToM = maxDistance,
    )
    // The distance is solved on its own first; the sweeps for the charts and tools follow once it settles,
    // and until then the previous ones for the same distance stay on screen.
    val solved by produceState<Solved?>(null, result, distance, targetSpeed, targetDirection, inputs) {
        val (point, lead) = withContext(Dispatchers.Default) {
            runCatching {
                val p = result.setup.pointAt(distance, solution.zeroElevationRad)
                p to targetSpeed?.let { result.setup.lead(distance, solution.zeroElevationRad, it, targetDirection) }
            }.getOrDefault(null to null)
        }
        value = Solved(distance, point, lead, value?.takeIf { it.distance == distance }?.analysis)
        if (point != null) {
            delay(300)
            value = Solved(distance, point, lead, withContext(Dispatchers.Default) {
                runCatching { TargetAnalysis.of(result.setup, solution.zeroElevationRad, distance, inputs) }.getOrNull()
            })
        }
    }
    // While dragging, the last solved distance stays on screen until the next one is ready.
    val current = solved?.takeIf { it.distance == distance || dragged != null }
    val analysis = current?.analysis?.takeIf { current.distance == distance }
    val outOfReach = current != null && current.point == null
    val selected = points.find { abs(it.rangeM - distance) < 1e-6 } ?: current?.point

    // In an SFP scope the reticle's angular size scales with calibrated / current magnification.
    // In an FFP scope reticle and target scale together, so only the field of view changes.
    val scale = if (sfp) magnification / calMag else 1.0
    val field = if (sfp) reticle.extent else reticle.extent * maxMag / magnification
    // Turret corrections for the chosen distance, in whole clicks of the turret unit, after the turret correction.
    val turret = result.turretUnit
    val elevationTurret = result.elevationTurret
    val windageTurret = result.windageTurret
    val upDial = elevationTurret.dial(-(selected?.dropRad ?: 0.0))
    val rightDial = windageTurret.dial(-(selected?.windageRad ?: 0.0))
    // What is dialed moves every hold point by the same amount: the angle the turret really moves.
    val correction = ui.correction(Reticles.designKey(reticle))
    // What the turrets are set to: the computed figure on a dialed axis, zero on a held one, unless turned by hand.
    val setUp = manualUp ?: if (correction.dialElevation) upDial else 0.0
    val setRight = manualRight ?: if (correction.dialWindage) rightDial else 0.0
    val dialX = -windageTurret.angle(setRight) / reticle.unit.radians * scale
    val dialY = -elevationTurret.angle(setUp) / reticle.unit.radians * scale
    val holds = (points + listOfNotNull(selected?.takeIf { it !in points })).map { p ->
        Hold(p, p.windageRad / reticle.unit.radians * scale - dialX, p.dropRad / reticle.unit.radians * scale - dialY)
    }
    // A moving target sits on the mark that is the lead away from the impact, against its motion.
    val leadHold = current?.lead?.let { l ->
        Hold(
            l.point,
            (l.point.windageRad - l.leadRad) / reticle.unit.radians * scale - dialX,
            l.point.dropRad / reticle.unit.radians * scale - dialY,
        )
    }

    // With the mean wind dialed, the lightest and strongest forecast wind are marks to hold beside the aim point.
    val gusts = analysis?.bracket?.takeIf { (correction.dialWindage || manualRight != null) && selected != null }?.let { b ->
        listOf(b.minMps to b.minRad, b.maxMps to b.maxRad)
            .filter { !it.second.isNaN() }
            .distinctBy { it.first }
            .map { (mps, rad) -> Gust(-rad / reticle.unit.radians * scale - dialX, mps.fmt(if (mps % 1.0 == 0.0) 0 else 1)) }
    }.orEmpty()

    // The wind as a flag in the field of view: speed and side from Conditions, the forecast's range under it.
    val flag = result.setup.conditions.winds.firstOrNull().let { w ->
        fun one(v: Double) = if (v % 1.0 == 0.0) v.fmt(0) else v.fmt(1)
        val lo = conditions.windMinMps.toNumber()?.takeIf { it >= 0 }
        val hi = conditions.windMaxMps.toNumber()?.takeIf { it >= 0 }
        val speed = w?.speedMps ?: 0.0
        WindFlag(
            speedMps = speed,
            fromRad = w?.fromRad ?: 0.0,
            label = "${one(speed)} m/s",
            range = if (lo != null || hi != null) "${one(lo ?: speed)}–${one(hi ?: speed)} m/s" else null,
        )
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The typed distance and the slider share one row; the arrows step a metre either way.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Focusing the field empties it for a fresh entry; the last distance stays as the placeholder.
            NumberField(
                stringResource(R.string.label_target_distance),
                dragged?.roundToInt()?.toString() ?: typed ?: distance.roundToInt().toString(),
                "m",
                Modifier.width(FIELD_WIDTH).onFocusChanged { f ->
                    if (f.isFocused && typed == null) typed = "" else if (!f.isFocused) typed = null
                },
                optional = true,
                placeholder = distance.roundToInt().toString(),
            ) { v ->
                typed = v
                if ((v.toNumber() ?: 0.0) > 0) update { it.copy(targetRangeM = v) }
            }
            IconButton(onClick = { setDistance(distance - 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.action_closer))
            }
            Slider(
                value = distance.toFloat().coerceIn(1f, maxDistance.toFloat()),
                onValueChange = { dragged = it },
                onValueChangeFinished = { dragged?.let { setDistance(it.toDouble()) }; dragged = null },
                valueRange = 1f..maxDistance.toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { setDistance(distance + 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.action_farther))
            }
            // A moving target is switched on by the running figure; its speed, course and hold appear when on.
            FilledTonalIconToggleButton(
                checked = conditions.targetMoving,
                onCheckedChange = { v -> update { it.copy(targetMoving = v) } },
            ) {
                Icon(painterResource(R.drawable.ic_moving_target), stringResource(R.string.label_moving_target))
            }
        }

        if (selected == null) {
            if (outOfReach) {
                Text(stringResource(R.string.target_out_of_reach, distance.roundToInt()), color = MaterialTheme.colorScheme.error)
            } else {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }

        // The advice sits in the corners the round lens leaves free, so the reticle stays in view.
        val advice = selected?.let { p ->
            val h = holds.first { it.point == p }
            val up = stringResource(if (upDial >= 0) R.string.up else R.string.down)
            val right = stringResource(if (rightDial >= 0) R.string.right else R.string.left)
            val vertical = stringResource(if (h.y <= 0) R.string.below_center else R.string.above_center)
            val horizontal = stringResource(if (h.x >= 0) R.string.right else R.string.left)
            // Each axis: dial whole clicks or hold on the reticle; what the clicks leave over is under half a click.
            val elevation = if (correction.dialElevation && manualUp == null) {
                stringResource(R.string.advice_dial, up, upDial.dialFmt(elevationTurret.click), turret.label)
            } else {
                stringResource(R.string.advice_hold, h.y.holdFmt(), reticle.unit.label, vertical)
            }
            val windage = if (correction.dialWindage && manualRight == null) {
                stringResource(R.string.advice_dial, right, rightDial.dialFmt(windageTurret.click), turret.label)
            } else {
                stringResource(R.string.advice_hold, h.x.holdFmt(), reticle.unit.label, horizontal)
            }
            // On a moving target the dialed elevation stands; only the lead is held, sideways.
            val moving = leadHold?.let { m ->
                val l = current?.lead ?: return@let null
                val parts = listOfNotNull(
                    if (correction.dialElevation && manualUp == null) null else stringResource(
                        R.string.hold_part, m.y.holdFmt(), reticle.unit.label,
                        stringResource(if (m.y <= 0) R.string.below_center else R.string.above_center),
                    ),
                    stringResource(
                        R.string.hold_part, m.x.holdFmt(), reticle.unit.label,
                        stringResource(if (m.x >= 0) R.string.right else R.string.left),
                    ),
                )
                stringResource(
                    R.string.moving_hold, parts.joinToString(", "),
                    stringResource(if (l.leadM >= 0) R.string.right else R.string.left), abs(l.leadM).fmt(2),
                )
            }
            val orDial = if (!correction.dialElevation && !correction.dialWindage && manualUp == null && manualRight == null) {
                stringResource(R.string.or_dial, up, upDial.dialFmt(elevationTurret.click), right, rightDial.dialFmt(windageTurret.click), turret.label)
            } else null
            Advice(elevation, windage, moving, orDial, abs(h.x) > reticle.extent || abs(h.y) > reticle.extent)
        }

        val leadText = advice?.moving?.takeIf { conditions.targetMoving }
            ?.let { stringResource(R.string.label_axis_advice, stringResource(R.string.label_moving), it) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val outerWidth = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The row stays in place with the moving target off (speed greyed out), so the lens never jumps.
        // A tap on the greyed field turns the moving target on and puts the cursor in it.
        val speedFocus = remember { FocusRequester() }
        var focusSpeed by remember { mutableStateOf(false) }
        var speedTyped by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(conditions.targetMoving, focusSpeed) {
            if (conditions.targetMoving && focusSpeed) {
                speedFocus.requestFocus()
                focusSpeed = false
            }
        }
        Box {
            NumberField(
                // Off, it reads 0 m/s; the typed speed is kept for when the moving target is turned back on.
                // As with the distance, focusing empties it for a fresh entry, the last speed staying as the placeholder.
                stringResource(R.string.label_target_speed),
                if (conditions.targetMoving) speedTyped ?: conditions.targetSpeedMps else "0",
                "m/s",
                Modifier.width(FIELD_WIDTH).focusRequester(speedFocus).onFocusChanged { f ->
                    if (f.isFocused && speedTyped == null) speedTyped = "" else if (!f.isFocused) speedTyped = null
                },
                optional = true,
                placeholder = conditions.targetSpeedMps,
                enabled = conditions.targetMoving,
            ) { v ->
                speedTyped = v
                if (v.toNumber() != null) update { it.copy(targetSpeedMps = v) }
            }
            if (!conditions.targetMoving) {
                Box(
                    Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.label_target_speed)) {
                        update { it.copy(targetMoving = true) }
                        focusSpeed = true
                    },
                )
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ReticleView(
                reticle, holds, selected, leadHold, leadText, gusts, flag, field,
                // The lens keeps clear of the turrets: they stand above it and to its right, as on the scope.
                Modifier.fillMaxSize().padding(top = TURRET_BAND, end = TURRET_BAND).pointerInput(weapon.id) {
                    // Two-finger pinch only, so a one-finger drag still scrolls the screen.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    magnification = (magnification * zoom).coerceIn(currentRange)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            )
            advice?.let { a ->
                // Both turrets, set as the settings say (computed figure dialed, or zero to hold); dragging a drum turns it.
                // Both are the same thickness and the same gap from the lens, centred on it.
                TurretView(
                    vertical = false,
                    value = setUp,
                    step = elevationTurret.click ?: (1.0 / 10),
                    figure = (if (setUp < 0) "↓" else "") + setUp.dialFmt(elevationTurret.click),
                    positiveLetter = "← " + stringResource(R.string.up_short),
                    negativeLetter = stringResource(R.string.down_short) + " →",
                    description = stringResource(R.string.turret_elevation, a.elevation),
                    onChange = { manualUp = it },
                    modifier = Modifier.align(Alignment.TopCenter).offset(x = -TURRET_BAND / 2).size(TURRET_LENGTH, TURRET_DEPTH),
                )
                val side = stringResource(if (setRight >= 0) R.string.right_short else R.string.left_short)
                TurretView(
                    vertical = true,
                    value = setRight,
                    step = windageTurret.click ?: (1.0 / 10),
                    figure = if (abs(setRight) < 1e-9) "0" else side + setRight.dialFmt(windageTurret.click),
                    positiveLetter = stringResource(R.string.right_short) + " ↑",
                    negativeLetter = stringResource(R.string.left_short) + " ↓",
                    description = stringResource(R.string.turret_windage, a.windage),
                    onChange = { manualRight = it },
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = TURRET_BAND / 2).size(TURRET_DEPTH, TURRET_LENGTH),
                )
                // Turned by hand: a round button in the free bottom right corner puts both back to the computed corrections
                // (the top right corner holds the moving target's course).
                if (manualUp != null || manualRight != null) {
                    FilledTonalIconButton(
                        onClick = { manualUp = null; manualRight = null },
                        modifier = Modifier.align(Alignment.BottomEnd).size(TURRET_DEPTH),
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_reset_turrets))
                    }
                }
            }
        }
        }
        // The course is set on a small dial above the lens, right of the elevation turret: up is away from the shooter,
        // right is to the right. It spans the speed row and the turret band, clear of the lens.
        if (conditions.targetMoving) {
            val free = (outerWidth + TURRET_BAND) / 2 - TURRET_LENGTH / 2 - 8.dp
            val course = minOf(free, 128.dp)
            // Light colours in any theme; a dark blue arrow stands out on it.
            MaterialTheme(colorScheme = COMPASS_COLORS) {
                CompassDial(
                    markers = listOf(DialMarker(targetDirection, DialArrow.OUTWARD, COURSE_BLUE, true)),
                    onChange = { v -> update { it.copy(targetDirectionDeg = v.roundToInt().mod(360).toString()) } },
                    centerText = "${targetDirection.roundToInt().mod(360)}°",
                    outerLabel = null,
                    stepDeg = 15.0,
                    maxSize = course,
                    hub = 0.26f,
                    arrowScale = 1.4f,
                    modifier = Modifier.align(Alignment.TopEnd).size(course),
                )
            }
        }
        }
        // What is held on the reticle for an axis not fully dialed, one line each under the lens.
        advice?.let { a ->
            if (!(correction.dialElevation && manualUp == null)) {
                AdviceText(stringResource(R.string.label_axis_advice, stringResource(R.string.label_elevation), a.elevation))
            }
            if (!(correction.dialWindage && manualRight == null)) {
                AdviceText(stringResource(R.string.label_axis_advice, stringResource(R.string.label_windage), a.windage))
            }
            a.orDial?.let { AdviceText(it) }
            if (a.outside) Text(stringResource(R.string.hold_outside_reticle), color = MaterialTheme.colorScheme.error)
        }
        // Reticle, magnification and the lock past the scope's range share one row: they change least often.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ReticleChip(reticle) { previewId = it.id }
            Text(
                stringResource(R.string.magnification_value, magnification.toDouble().magFmt()),
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = magnification.coerceIn(range),
                onValueChange = { magnification = it },
                valueRange = range,
                modifier = Modifier.weight(1f),
            )
            IconToggleButton(
                checked = unlocked,
                onCheckedChange = {
                    unlocked = it
                    if (!unlocked) magnification = magnification.coerceIn(minMag.toFloat(), maxMag.toFloat())
                },
            ) {
                Icon(
                    painterResource(if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock),
                    stringResource(R.string.action_unlock_magnification),
                )
            }
        }
        if (magnification < minMag - 1e-3 || magnification > maxMag + 1e-3) {
            Text(
                stringResource(R.string.magnification_outside_range, minMag.magFmt(), maxMag.magFmt()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (previewId != own.id) {
            Text(
                stringResource(R.string.reticle_preview_note, weapon.scopeName.ifBlank { weapon.name }, stringResource(own.name)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (sfp) Text(stringResource(R.string.sfp_note, calMag.magFmt()), style = MaterialTheme.typography.bodySmall)

        TargetTools(result, conditions, update, analysis, inputs, reticle, scale, ui.charts, { setDistance(it) }, onSaveAmmo, onAddAmmoCopy)
        if (analysis != null && ui.charts.any { it in ChartKind.card }) {
            Collapsible(stringResource(R.string.section_charts)) { TargetCharts(result, analysis, distance, ui.charts) { setDistance(it) } }
        }
    }
}

/** Hold advice on a faint plate, so it reads as advice and stands apart from the controls. */
@Composable
private fun AdviceText(text: String, color: Color = Color.Unspecified, align: TextAlign = TextAlign.Start) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.bodyMedium, textAlign = align)
    }
}

/** The reticle as a small chip that drops down every design, with its unit where it comes in both. */
@Composable
private fun ReticleChip(selected: Reticle, onSelect: (Reticle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(stringResource(selected.family) + " ▾", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Reticles.families(selected.unit).flatMap { Reticles.variants(it) }.distinctBy { it.id }.forEach { r ->
                val both = Reticles.variants(r).size > 1
                DropdownMenuItem(
                    text = { Text(stringResource(r.family) + if (both) " (${r.unit.label})" else "") },
                    onClick = { onSelect(r); open = false },
                )
            }
        }
    }
}

/**
 * Picks a reticle design, then its MRAD or MOA version when it comes in both.
 * A newly picked design starts in [preferredUnit] when it has that version.
 */
@Composable
internal fun ReticleChoice(
    label: String,
    selected: Reticle,
    preferredUnit: AngularUnit,
    name: @Composable (Reticle) -> String,
    onSelect: (Reticle) -> Unit,
) {
    Choice(label, selected, Reticles.families(preferredUnit), name) { onSelect(Reticles.variant(it, preferredUnit)) }
    val variants = Reticles.variants(selected)
    if (variants.size > 1) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.label_reticle_units), Modifier.weight(1f))
            variants.forEach { v ->
                FilterChip(selected = v.id == selected.id, onClick = { onSelect(v) }, label = { Text(v.unit.label) })
            }
        }
    }
}

private fun Double.fmt(decimals: Int) = "%.${decimals}f".format(this)
private fun Double.magFmt() = if (abs(this - roundToInt()) < 0.05) roundToInt().toString() else fmt(1)
/** A hold, to the nearest 0.05 of the reticle unit. */
private fun Double.holdFmt() = ((abs(this) / 0.05).roundToInt() * 0.05).fmt(2)

/** A turret correction, with as many decimals as the click value needs. */
private fun Double.dialFmt(click: Double?): String {
    val decimals = click?.let { c -> (0..3).firstOrNull { d -> abs(c * Math.pow(10.0, d.toDouble()) - (c * Math.pow(10.0, d.toDouble())).roundToInt()) < 1e-6 } } ?: 1
    return abs(this).fmt(decimals)
}

private val TURRET_LENGTH = 136.dp
private val TURRET_DEPTH = 46.dp
/** The strip above and right of the lens: a turret and the same gap to the lens on both sides. */
private val TURRET_BAND = TURRET_DEPTH + 6.dp
/** Distance and target speed fields share one width. */
private val FIELD_WIDTH = 132.dp
private val MOVING_BLUE = Color(0xFF1E88E5)
private val COURSE_BLUE = Color(0xFF0D47A1)
private val COMPASS_COLORS = lightColorScheme(
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F3EF),
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF424242),
    primary = COURSE_BLUE,
)

/** The wind for the flag; [fromRad] as in core Wind: 0 from the target, π/2 from the right. */
private class WindFlag(val speedMps: Double, val fromRad: Double, val label: String, val range: String?)

/** How far a flag stands out from the pole, degrees from hanging, by Beaufort force (0–6). */
private fun flagAngle(mps: Double): Double = when {
    mps < 0.3 -> 0.0
    mps < 1.6 -> 10.0
    mps < 3.4 -> 30.0
    mps < 5.5 -> 45.0
    mps < 8.0 -> 60.0
    mps < 10.8 -> 80.0
    else -> 90.0
}

private val FLAG_GREEN = Color(0xFF2E7D32)

/**
 * A green flag on a pole whose top is at [top]: the hoist stays on the pole and the fly stands out by the
 * Beaufort angle, downwind. Seen from behind the rifle, a head or tail wind shortens it; past force 6 it flutters.
 */
private fun DrawScope.windFlag(f: WindFlag, top: Offset, measurer: TextMeasurer) {
    val pole = 46.dp.toPx()
    val h = 22.dp.toPx() * 0.7f
    val len = 38.dp.toPx() * 0.7f
    val a = Math.toRadians(maxOf(flagAngle(f.speedMps), 8.0))
    val side = -sign(sin(f.fromRad)).let { if (it == 0.0) -1.0 else it }  // from the right blows to the left
    val cross = maxOf(abs(sin(f.fromRad)), 0.25)
    val d = Offset((side * sin(a) * cross).toFloat(), cos(a).toFloat()) * len
    val wave = if (flagAngle(f.speedMps) >= 30) 2.dp.toPx() else 0f
    drawLine(Color(0xFF37474F), top, top + Offset(0f, pole), 2.dp.toPx())
    val cloth = Path().apply {
        moveTo(top.x, top.y)
        lineTo(top.x + d.x / 2, top.y + d.y / 2 - wave)
        lineTo(top.x + d.x, top.y + d.y)
        lineTo(top.x + d.x, top.y + h + d.y)
        lineTo(top.x + d.x / 2, top.y + h + d.y / 2 - wave)
        lineTo(top.x, top.y + h)
        close()
    }
    drawPath(cloth, FLAG_GREEN)
    if (flagAngle(f.speedMps) >= 90) {
        val e = top + Offset(d.x + (side * 5.dp.toPx()).toFloat(), h / 2)
        drawLine(FLAG_GREEN, e + Offset(0f, -4.dp.toPx()), e + Offset(0f, 4.dp.toPx()), 1.5.dp.toPx())
        val e2 = e + Offset((side * 4.dp.toPx()).toFloat(), 0f)
        drawLine(FLAG_GREEN, e2 + Offset(0f, -6.dp.toPx()), e2 + Offset(0f, 6.dp.toPx()), 1.5.dp.toPx())
    }
    // Speed beside the pole on the side away from the cloth.
    val big = measurer.measure(f.label, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FLAG_GREEN))
    val x = if (side < 0) top.x + 5.dp.toPx() else top.x - 5.dp.toPx() - big.size.width
    drawText(big, topLeft = Offset(x, top.y + h))
    f.range?.let { r ->
        val small = measurer.measure(r, TextStyle(fontSize = 10.sp, color = FLAG_GREEN))
        val x2 = if (side < 0) top.x + 5.dp.toPx() else top.x - 5.dp.toPx() - small.size.width
        drawText(small, topLeft = Offset(x2, top.y + h + big.size.height))
    }
}

/** A wind speed's hold beside the aim point, [x] in reticle units like [Hold.x]. */
private class Gust(val x: Double, val label: String)

private class Advice(val elevation: String, val windage: String, val moving: String?, val orDial: String?, val outside: Boolean)

@Composable
private fun ReticleView(
    reticle: Reticle,
    holds: List<Hold>,
    selected: TrajectoryPoint?,
    lead: Hold?,
    leadText: String?,
    gusts: List<Gust>,
    flag: WindFlag,
    field: Double,
    modifier: Modifier,
) {
    val text = rememberTextMeasurer()
    val ink = Color(0xFF111111)
    val hold = Color(0xFFE53935)
    val moving = Color(0xFF1E88E5)
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        val px = radius / field
        fun at(x: Double, y: Double) = Offset(c.x + (x * px).toFloat(), c.y - (y * px).toFloat())
        // Crosshair arms drawn to the reticle's outer part run on to the edge of the field of view, as in the scope.
        val edge = maxOf(field, reticle.extent) * 1.5
        fun reach(v: Double) = if (abs(v) >= reticle.extent * 0.8) sign(v) * maxOf(abs(v), edge) else v
        fun arm(m: Mark.Line): Mark.Line = when {
            m.x1 == 0.0 && m.x2 == 0.0 -> m.copy(y1 = reach(m.y1), y2 = reach(m.y2))
            m.y1 == 0.0 && m.y2 == 0.0 -> m.copy(x1 = reach(m.x1), x2 = reach(m.x2))
            else -> m
        }

        val lens = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, radius)) }
        clipPath(lens) {
            drawRect(Brush.radialGradient(listOf(Color(0xFFF4F6F2), Color(0xFFC9D2C9)), center = c, radius = radius))
            reticle.marks.forEach { m ->
                when (m) {
                    is Mark.Line -> arm(m).let { l -> drawLine(
                        ink, at(l.x1, l.y1), at(l.x2, l.y2),
                        strokeWidth = when (m.weight) {
                            Weight.FINE -> 1.2.dp.toPx()
                            Weight.MEDIUM -> 2.dp.toPx()
                            Weight.HEAVY -> 5.dp.toPx()
                        },
                        cap = StrokeCap.Butt,
                    ) }
                    is Mark.Dot -> drawCircle(ink, maxOf((m.r * px).toFloat(), 1.5.dp.toPx()), at(m.x, m.y))
                    is Mark.Label -> label(text, m.text, at(m.x, m.y), ink)
                }
            }
            holds.forEach { h ->
                val isSelected = h.point == selected
                val p = at(h.x, h.y)
                drawCircle(hold, if (isSelected) 5.dp.toPx() else 3.dp.toPx(), p)
                if (isSelected) drawCircle(hold, 9.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                // The chosen distance is in the field above, so its dot needs no figure; the others sit on the
                // side away from the vertical line, so they never cover the reticle's marks.
                if (!isSelected && h.point.rangeM.roundToInt() % 100 == 0) {
                    val layout = text.measure("${h.point.rangeM.roundToInt()}", TextStyle(fontSize = 10.sp, color = hold))
                    val gap = 6.dp.toPx()
                    val x = if (h.x < 0) p.x - gap - layout.size.width else p.x + gap
                    drawText(layout, topLeft = Offset(x, p.y - layout.size.height / 2f))
                }
            }
            // Lightest and strongest wind: orange bars across the aim point's row, speed under each.
            holds.firstOrNull { it.point == selected }?.let { aim ->
                val orange = Color(0xFFFB8C00)
                val bar = 10.dp.toPx()
                // The left mark's speed ends at its bar and the right one's starts at it, so close marks never merge.
                val sorted = gusts.sortedBy { it.x }
                sorted.forEachIndexed { i, g ->
                    val p = at(g.x, aim.y)
                    drawLine(orange, p - Offset(0f, bar), p + Offset(0f, bar), 3.dp.toPx())
                    val layout = text.measure(g.label, TextStyle(fontSize = 10.sp, color = orange))
                    val x = when {
                        sorted.size < 2 -> p.x - layout.size.width / 2f
                        i == 0 -> p.x - 2.dp.toPx() - layout.size.width
                        else -> p.x + 2.dp.toPx()
                    }
                    drawText(layout, topLeft = Offset(x, p.y + bar + 2.dp.toPx()))
                }
            }
            lead?.let { h ->
                val p = at(h.x, h.y)
                drawCircle(moving, 5.dp.toPx(), p)
                drawCircle(moving, 9.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                // Its hold floats beside it on a faint plate, kept inside the lens's square.
                leadText?.let { t ->
                    val layout = text.measure(t, TextStyle(fontSize = 11.sp, color = Color(0xFF1565C0)))
                    val pad = 4.dp.toPx()
                    val w = layout.size.width + 2 * pad
                    val hgt = layout.size.height + 2 * pad
                    val gap = 12.dp.toPx()
                    var x = p.x + gap
                    if (x + w > size.width) x = p.x - gap - w
                    val y = (p.y + gap).coerceAtMost(size.height - hgt)
                    drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(x, y), Size(w, hgt), CornerRadius(4.dp.toPx()))
                    drawText(layout, topLeft = Offset(x + pad, y + pad))
                }
            }
        }
        // Upper left of the lens: clear of the corner texts, the moving-target hold in the bottom left and most reticle marks.
        clipPath(lens) { windFlag(flag, c + Offset(-radius * 0.70f, -radius * 0.42f), text) }
        drawCircle(Color(0xFF263238), radius, c, style = Stroke(4.dp.toPx()))
    }
}

private fun DrawScope.label(measurer: TextMeasurer, s: String, center: Offset, color: Color) {
    val layout = measurer.measure(s, TextStyle(fontSize = 10.sp, color = color))
    drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}

private fun clock(deg: Double): String = clockText(deg)

/** Spin drift by distance and the windage correction by wind speed and by wind direction at the chosen distance. */
@Composable
private fun TargetCharts(result: CalcResult.Ok, a: TargetAnalysis, distance: Double, charts: Set<ChartKind>, onUseDistance: (Double) -> Unit) {
    val unit = result.turretUnit
    val digits = unit.decimals
    fun correction(v: Double): String = when {
        abs(v) < 0.5 * 10.0.pow(-digits) -> "0"
        v > 0 -> "R" + abs(v).fmt(digits)
        else -> "L" + abs(v).fmt(digits)
    }
    val windSpeed = result.setup.conditions.winds.firstOrNull()?.speedMps ?: 0.0
    val clockLabel = stringResource(R.string.wind_from_clock)
    val windFrom = result.setup.conditions.winds.firstOrNull()?.fromRad?.let { Math.toDegrees(it).mod(360.0) }

    LineChart(
        title = stringResource(R.string.chart_wind_speed, unit.label),
        series = TargetAnalysis.DIRECTIONS.mapIndexed { i, d ->
            ChartSeries(
                stringResource(R.string.wind_from_clock, clock(d)),
                chartColors[i % chartColors.size],
                a.bySpeed[d].orEmpty().map { (s, c) -> s to c / unit.radians },
            )
        },
        xTicks = TargetAnalysis.SPEEDS.filter { it.roundToInt() % 2 == 0 },
        xLabel = { it.roundToInt().toString() },
        yLabel = ::correction,
        markerX = windSpeed.takeIf { it > 0 },
        markAllSeries = true,
        xValue = { "${it.fmt(1)} m/s" },
    )
    LineChart(
        title = stringResource(R.string.chart_wind_direction, unit.label),
        series = TargetAnalysis.DIRECTION_SPEEDS.mapIndexed { i, s ->
            ChartSeries(
                stringResource(R.string.wind_speed_series, s.roundToInt()),
                chartColors[i % chartColors.size],
                a.byDirection[s].orEmpty().map { (d, c) -> d to c / unit.radians },
            )
        },
        xTicks = (0..12).map { it * 30.0 },
        xLabel = { clock(it) },
        yLabel = ::correction,
        markerX = windFrom,
        markAllSeries = true,
        xValue = { clockLabel.format(clock(it)) },
    )

    val drift = result.solution.points.filter { it.rangeM > 0 }
    if (ChartKind.SPIN_DRIFT !in charts) {
        // Turned off in Settings.
    } else if (drift.any { it.spinDriftM != 0.0 }) {
        // Spin drift pushes the bullet one way and grows with distance; the correction is the other way.
        // The chart shows how much, rising, with the direction in the axis labels.
        fun corr(p: TrajectoryPoint) = -atan2(p.spinDriftM, p.rangeM) / unit.radians
        val side = if (drift.sumOf { corr(it) } >= 0) "R" else "L"
        fun held(v: Double) = if (abs(v) < 0.5 * 10.0.pow(-digits)) "0" else side + abs(v).fmt(digits)
        val p = a.point
        val pointText = stringResource(R.string.spin_drift_point)
        val driftCm = drift.map { it.rangeM to abs(it.spinDriftM) * 100 }
        val series = drift.map { it.rangeM to abs(corr(it)) }
        LineChart(
            title = stringResource(R.string.chart_spin_drift, unit.label),
            series = listOf(ChartSeries("", chartColors[0], series)),
            xTicks = niceTicks(0.0, drift.last().rangeM).filter { it <= drift.last().rangeM },
            xLabel = { it.roundToInt().toString() },
            yLabel = ::held,
            markerX = distance,
            xValue = { "${it.roundToInt()} m" },
            points = listOf(
                ChartPoint(
                    p.rangeM, abs(corr(p)), chartColors[0],
                    AnnotatedString("${held(corr(p))} ${unit.label}"),
                    stringResource(R.string.spin_drift_point, (abs(p.spinDriftM) * 100).fmt(1), p.rangeM.roundToInt()),
                ),
            ),
            readout = { x ->
                listOfNotNull(series.valueAt(x)?.let { v ->
                    ChartPoint(
                        x, v, chartColors[0], AnnotatedString("${held(v * sign(corr(p)))} ${unit.label}"),
                        pointText.format((driftCm.valueAt(x) ?: 0.0).fmt(1), x.roundToInt()),
                    )
                })
            },
            onLongPress = onUseDistance,
        )
    } else {
        Text(
            stringResource(if (result.setup.options.spinDrift) R.string.spin_drift_needs_data else R.string.spin_drift_off),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Coriolis and Eötvös by direction of fire, without wind, at the chosen distance.
    val azimuthCharts = ChartKind.AZIMUTH_WINDAGE in charts || ChartKind.AZIMUTH_ELEVATION in charts
    if (!azimuthCharts) {
        // Turned off in Settings.
    } else if (a.byAzimuth.size > 1) {
        val az = Math.toDegrees(result.setup.conditions.azimuthRad).mod(360.0)
        fun at(f: (AzimuthRow) -> Double): Double {
            val rows = a.byAzimuth
            val i = rows.indexOfFirst { it.azimuthDeg >= az }.takeIf { it > 0 } ?: return f(rows.first())
            val (p, q) = rows[i - 1] to rows[i]
            val k = (az - p.azimuthDeg) / (q.azimuthDeg - p.azimuthDeg)
            return f(p) + k * (f(q) - f(p))
        }
        val range = a.point.rangeM.roundToInt()
        val where = "${az.roundToInt()}° · $range m"
        fun compass(deg: Double) = when (deg.roundToInt()) { 0, 360 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "" }
        fun upDown(v: Double) = if (abs(v) < 0.5 * 10.0.pow(-(digits + 1))) "0" else (if (v > 0) "↑" else "↓") + abs(v).fmt(digits + 1)
        val windage = at { it.windageRad } / unit.radians
        if (ChartKind.AZIMUTH_WINDAGE in charts) LineChart(
            title = stringResource(R.string.chart_azimuth_windage, unit.label, range),
            series = listOf(ChartSeries("", chartColors[0], a.byAzimuth.map { it.azimuthDeg to it.windageRad / unit.radians })),
            xTicks = (0..4).map { it * 90.0 },
            xLabel = ::compass,
            yLabel = ::correction,
            markerX = az,
            xValue = { "${it.roundToInt().mod(360)}° ${compassPoint(it)}" },
            points = listOf(ChartPoint(az, windage, chartColors[0], AnnotatedString("${correction(windage)} ${unit.label}"), where)),
            readout = { x ->
                val rows = a.byAzimuth.map { it.azimuthDeg to it.windageRad / unit.radians }
                listOfNotNull(rows.valueAt(x)?.let { v -> ChartPoint(x, v, chartColors[0], AnnotatedString("${correction(v)} ${unit.label}"), "${x.roundToInt()}° · $range m") })
            },
        )
        val change = at { it.elevationChangeRad } / unit.radians
        if (ChartKind.AZIMUTH_ELEVATION in charts) LineChart(
            title = stringResource(R.string.chart_azimuth_elevation, unit.label, range),
            series = listOf(ChartSeries("", chartColors[1], a.byAzimuth.map { it.azimuthDeg to it.elevationChangeRad / unit.radians })),
            xTicks = (0..4).map { it * 90.0 },
            xLabel = ::compass,
            yLabel = ::upDown,
            markerX = az,
            xValue = { "${it.roundToInt().mod(360)}° ${compassPoint(it)}" },
            points = listOf(ChartPoint(az, change, chartColors[1], AnnotatedString("${upDown(change)} ${unit.label}"), where)),
            readout = { x ->
                val rows = a.byAzimuth.map { it.azimuthDeg to it.elevationChangeRad / unit.radians }
                listOfNotNull(rows.valueAt(x)?.let { v -> ChartPoint(x, v, chartColors[1], AnnotatedString("${upDown(v)} ${unit.label}"), "${x.roundToInt()}° · $range m") })
            },
        )
    } else {
        Text(stringResource(R.string.azimuth_charts_need_coriolis), style = MaterialTheme.typography.bodySmall)
    }
}
