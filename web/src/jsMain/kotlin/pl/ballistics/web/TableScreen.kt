package pl.ballistics.web

import org.w3c.dom.HTMLElement
import pl.ballistics.core.TrajectoryPoint
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
                    el("p", "hint", S.tableLegend(result.unit.label, result.weapon.clickValue))
                }
                root.el("div", "table-wrap") {
                    el("table", "range-table") {
                        el("thead") {
                            el("tr") { S.columns.forEach { el("th", text = it) } }
                        }
                        el("tbody") {
                            s.points.forEach { p -> el("tr") { cells(p, result.unit.radians, result.clickValue).forEach { el("td", text = it) } } }
                        }
                    }
                }
            }
        }
    }

    private fun cells(p: TrajectoryPoint, unitRad: Double, click: Double?): List<String> {
        // Every column is the correction, opposite to the impact offset: the hold in cm, then the angle
        // and clicks to dial. Direction by arrow or letter, never a minus sign.
        val up = -p.dropRad / unitRad
        val right = -p.windageRad / unitRad
        val upCm = -p.dropM * 100
        val rightCm = -p.windageM * 100
        val shown = p.rangeM > 0
        return listOf(
            p.rangeM.roundToInt().toString(),
            vertical(upCm, 0.05) + grouped(fmt(abs(upCm), 1)),
            if (shown) vertical(up, 0.005) + fmt(abs(up), 2) else "",
            if (shown && click != null) vertical(up / click, 0.5) + abs(up / click).roundToInt() else "",
            side(rightCm, 0.05) + grouped(fmt(abs(rightCm), 1)),
            if (shown) side(right, 0.005) + fmt(abs(right), 2) else "",
            if (shown && click != null) side(right / click, 0.5) + (abs(right) / click).roundToInt() else "",
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

    /** A narrow gap before the hundreds in figures of 1000 and over (1 320.8), so the thousands read at a glance. */
    fun grouped(figure: String): String {
        val digits = figure.indexOfFirst { !it.isDigit() }.let { if (it < 0) figure.length else it }
        return if (digits > 3) figure.substring(0, digits - 3) + '\u202F' + figure.substring(digits - 3) else figure
    }

    /** ↑ or ↓ for a correction up or down; nothing within [dead] of zero. */
    fun vertical(up: Double, dead: Double) = when {
        up > dead -> "↑"
        up < -dead -> "↓"
        else -> ""
    }
}
