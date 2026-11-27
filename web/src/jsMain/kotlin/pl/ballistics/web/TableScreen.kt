package pl.ballistics.web

import org.w3c.dom.HTMLElement
import pl.ballistics.core.TrajectoryPoint
import pl.ballistics.core.Turret
import kotlin.math.abs
import kotlin.math.roundToInt

/** The range card for the active rifle, as on Android. */
object TableScreen {

    fun render(root: HTMLElement) {
        val out = root.el("div")
        root.card {
            val c = Store.data.conditions
            row {
                numberField(S.labelTableRange, c.maxRangeM, "m") { v -> Store.updateConditions { it.copy(maxRangeM = v) } }
                numberField(S.labelTableStep, c.stepM, "m") { v -> Store.updateConditions { it.copy(stepM = v) } }
            }
        }
        fun draw() {
            out.clear()
            table(out, Store.result)
        }
        draw()
        Store.onChange(::draw)
    }

    private fun table(root: HTMLElement, result: CalcResult) {
        when (result) {
            is CalcResult.Error -> root.card { el("p", "error", result.message) }
            is CalcResult.Ok -> {
                val s = result.solution
                root.card {
                    el("div", "list-title", "${result.weapon.name} · ${result.ammo.name}")
                    el("p", "summary", listOfNotNull(
                        S.summaryMuzzleVelocity(s.muzzleVelocityMps.roundToInt()),
                        S.summaryDensityAltitude(s.densityAltitudeM.roundToInt()),
                        s.stabilityFactor?.let { S.summaryStability(fmt(it, 2)) },
                    ).joinToString("  ·  "))
                    s.stabilityFactor?.let {
                        if (it < 1.0) el("p", "error", S.warningUnstable) else if (it < 1.4) el("p", "error", S.warningMarginal)
                    }
                    el("p", "hint", S.tableLegend(result.unit.label, result.weapon.clickValue) + turretNote(result))
                }
                root.el("div", "table-wrap") {
                    el("table", "range-table") {
                        el("thead") {
                            el("tr") { S.columns.forEach { el("th", text = it) } }
                        }
                        el("tbody") {
                            s.points.forEach { p -> el("tr") { cells(p, result.elevationTurret, result.windageTurret).forEach { el("td", text = it) } } }
                        }
                    }
                }
            }
        }
    }

    /** The turret correction, when it changes the table. */
    private fun turretNote(result: CalcResult.Ok): String =
        result.elevationTurret.factor.takeIf { it != 1.0 }?.let { " " + S.factorLegend(fmt(it, 3)) }.orEmpty()

    private fun cells(p: TrajectoryPoint, elevation: Turret, windage: Turret): List<String> {
        // The cm columns are where the bullet goes: drop below the line of sight (negative only above it,
        // between the near and far zero) and drift, unsigned: the wind tells which way. The angle and clicks
        // columns are the corrections to dial, after the turret correction.
        val up = elevation.units(-p.dropRad)
        val right = windage.units(-p.windageRad)
        val click = elevation.click
        val windClick = windage.click
        val dropCm = -p.dropM * 100
        val driftCm = p.windageM * 100
        val shown = p.rangeM > 0
        return listOf(
            p.rangeM.roundToInt().toString(),
            (if (dropCm < -0.05) "-" else "") + grouped(fmt(abs(dropCm), 1)),
            if (shown) vertical(up, 0.005) + fmt(abs(up), 2) else "",
            if (shown && click != null) vertical(up / click, 0.5) + abs(up / click).roundToInt() else "",
            grouped(fmt(abs(driftCm), 1)),
            if (shown) side(right, 0.005) + fmt(abs(right), 2) else "",
            if (shown && windClick != null) side(right / windClick, 0.5) + (abs(right) / windClick).roundToInt() else "",
            p.velocityMps.roundToInt().toString(),
            p.energyJ.roundToInt().toString(),
            fmt(p.timeS, 3),
            fmt(p.mach, 2),
        )
    }

    /** R or L for a correction to the right or left; nothing within [dead] of zero, where the figure rounds to 0. */
    fun side(right: Double, dead: Double = 0.005) = when {
        right > dead -> "R"
        right < -dead -> "L"
        else -> ""
    }

    /** A narrow gap between metres and centimetres in figures of 100 cm and over (13 20.8), so the metres read at a glance. */
    fun grouped(figure: String): String {
        val digits = figure.indexOfFirst { !it.isDigit() }.let { if (it < 0) figure.length else it }
        return if (digits > 2) figure.substring(0, digits - 2) + '\u202F' + figure.substring(digits - 2) else figure
    }

    /** Up is the usual case and goes unmarked; ↓ marks a correction down (between the near and far zero). */
    fun vertical(up: Double, dead: Double) = when {
        up < -dead -> "↓"
        else -> ""
    }
}
