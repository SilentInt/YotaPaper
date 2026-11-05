package com.yota.launcher.data.grid

import kotlin.math.roundToInt

/**
 * Pure grid sizing math shared by the grid [com.yota.launcher.ui.EInkLauncherView],
 * the settings screen and the recent-task panel.
 *
 * Everything is expressed in density-independent pixels (dp) or screen ratios,
 * so the same numbers work on a Yota3 back screen, a phone, a tablet or a
 * sideways e-ink reader.
 *
 * Preferences use 0 to mean "auto":
 * - the app drawer defaults to roughly 4 columns x 6 rows on a phone-sized
 *   panel and gains rows/columns on larger panels,
 * - the home grid defaults to roughly 4 columns x 4 rows,
 * - the icon and label sizes are always derived from the cell size; there is no
 *   manual icon-size setting any more.
 */
object GridSizing {

    /**
     * Nominal cell sizes used to derive the automatic grid (dp). They include the
     * label line and the dividers, which is why they are larger than a bare icon.
     */
    private const val AUTO_CELL_WIDTH_DP = 82f
    private const val AUTO_CELL_HEIGHT_DP = 90f
    private const val AUTO_CELL_HOME_HEIGHT_DP = 80f

    /**
     * Smallest cell that still renders a legible icon + one label line (dp).
     * The manual range is capped by THIS, not by the preferred auto cell size,
     * so a 360dp panel can still choose 6-8 columns if the user wants density.
     * Icon and label sizes shrink with the cell below.
     */
    private const val MIN_CELL_WIDTH_DP = 40f
    private const val MIN_CELL_HEIGHT_DP = 44f
    private const val MIN_CELL_HOME_HEIGHT_DP = 40f

    /** Hard limits; mirrors the bounds enforced by [com.yota.launcher.ui.EInkLauncherView]. */
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 12
    const val MIN_ROWS = 1
    const val MAX_ROWS = 14

    /**
     * Manual selection range offered in settings. Wider than automatic mode so
     * the user can go denser or sparser; the grid still clamps to what the panel
     * can ever render (MIN/MAX above), and icon/label sizes shrink to fit.
     */
    const val MANUAL_MIN_COLUMNS = 2
    const val MANUAL_MAX_COLUMNS = 10
    const val MANUAL_MIN_ROWS = 2
    const val MANUAL_MAX_ROWS = 12
    const val MANUAL_MIN_HOME_ROWS = 1
    const val MANUAL_MAX_HOME_ROWS = 10

    /** Upper bounds for the recent-task panel so it stays a bottom sheet. */
    const val RECENT_MAX_COLUMNS = 5
    const val RECENT_MAX_ROWS = 5

    /** Range cap for automatic mode (no manual preference stored). */
    const val AUTO_MIN_COLUMNS = 4
    const val AUTO_MAX_COLUMNS = 6
    const val AUTO_MAX_ROWS = 7
    const val AUTO_MAX_HOME_ROWS = 6

    /** Absolute icon bounds (dp). */
    const val MIN_ICON_DP = 16
    const val MAX_ICON_DP = 160

    /**
     * Single-row / single-column grids keep icons modest so they do not fill the
     * whole cell. Multiplier per row/column; also caps the icon at 60 dp.
     */
    const val SINGLE_ROW_FACTOR = 0.62f
    const val SINGLE_COLUMN_FACTOR = 0.72f
    private const val ROOMY_ICON_CAP_DP = 60f

    /** Label line (~14sp) + gap + small breathing room (dp). */
    private const val LABEL_RESERVE_DP = 18f

    // ------------------------------------------------------------------ screen caps

    /**
     * How many rows/columns the screen can host when the user asks for a
     * maximum. The cap comes from the smallest legible cell, not from the
     * preferred automatic cell, so manual choices can go denser than the
     * automatic default (a 360dp panel reaches 7-8 columns / 11-12 rows).
     */
    fun columnCap(widthDp: Float): Int =
        cap(widthDp, MIN_CELL_WIDTH_DP, rangeMin = 3, ceiling = MANUAL_MAX_COLUMNS)

    fun rowCap(heightDp: Float): Int =
        cap(heightDp, MIN_CELL_HEIGHT_DP, rangeMin = 2, ceiling = MANUAL_MAX_ROWS)

    /** Home rows are shorter cells than the drawer; cap from the home row height. */
    fun homeRowCap(heightDp: Float): Int =
        cap(heightDp, MIN_CELL_HOME_HEIGHT_DP, rangeMin = 2, ceiling = MANUAL_MAX_HOME_ROWS)

    /** Recent-task panel is a bottom sheet: keep its caps small. */
    fun recentColumnCap(widthDp: Float): Int =
        cap(widthDp, AUTO_CELL_WIDTH_DP, rangeMin = 3, ceiling = RECENT_MAX_COLUMNS)

    fun recentRowCap(heightDp: Float): Int =
        cap(heightDp, AUTO_CELL_HEIGHT_DP, rangeMin = 1, ceiling = RECENT_MAX_ROWS)

    fun columnOptions(cap: Int): IntArray =
        (MANUAL_MIN_COLUMNS..cap.coerceIn(MANUAL_MIN_COLUMNS, MANUAL_MAX_COLUMNS)).toList().toIntArray()

    fun rowOptions(cap: Int): IntArray =
        (MANUAL_MIN_ROWS..cap.coerceIn(MANUAL_MIN_ROWS, MANUAL_MAX_ROWS)).toList().toIntArray()

    fun homeRowOptions(cap: Int): IntArray =
        (MANUAL_MIN_HOME_ROWS..cap.coerceIn(MANUAL_MIN_HOME_ROWS, MANUAL_MAX_HOME_ROWS)).toList().toIntArray()

    private fun cap(length: Float, cellDp: Float, rangeMin: Int, ceiling: Int): Int {
        val safe = if (length.isFinite()) length else cellDp * rangeMin
        val value = (safe / cellDp).roundToInt()
        return value.coerceIn(maxOf(MIN_COLUMNS, rangeMin), ceiling)
    }

    // ------------------------------------------------------------------ automatic grid

    /** 360dp wide phones get 4 columns; wider panels get up to 6. */
    fun autoColumnsFor(widthDp: Float): Int =
        ((if (widthDp.isFinite()) widthDp else 0f) / AUTO_CELL_WIDTH_DP).roundToInt()
            .coerceIn(AUTO_MIN_COLUMNS, AUTO_MAX_COLUMNS)

    /** ~530dp tall app drawer gets 6 rows; long screens get up to 7. */
    fun autoRowsFor(heightDp: Float): Int =
        ((if (heightDp.isFinite()) heightDp else 0f) / AUTO_CELL_HEIGHT_DP).roundToInt()
            .coerceIn(2, AUTO_MAX_ROWS)

    /** ~320dp tall home grid gets 4 rows; up to 6. */
    fun autoHomeRows(heightDp: Float): Int =
        ((if (heightDp.isFinite()) heightDp else 0f) / AUTO_CELL_HOME_HEIGHT_DP).roundToInt()
            .coerceIn(1, AUTO_MAX_HOME_ROWS)

    /** Automatic grid as (columns, rows). */
    fun autoGrid(widthDp: Float, heightDp: Float, home: Boolean = false): Pair<Int, Int> =
        autoColumnsFor(widthDp) to
            (if (home) autoHomeRows(heightDp) else autoRowsFor(heightDp))

    // ------------------------------------------------------------------ preferences

    /**
     * Normalises a stored preference into the grid that will actually be drawn.
     * 0 means automatic. Manual values are clamped to the hard renderable range
     * (never to the auto range) so a user choice is not silently shrunk; only a
     * grid that the panel physically cannot host is reduced.
     */
    fun resolveColumns(preference: Int, widthDp: Float, cap: Int = MANUAL_MAX_COLUMNS): Int {
        val chosen = if (preference > 0) preference else autoColumnsFor(widthDp)
        return chosen.coerceIn(MIN_COLUMNS, maxOf(MIN_COLUMNS, cap))
    }

    fun resolveRows(
        preference: Int,
        heightDp: Float,
        cap: Int = MANUAL_MAX_ROWS,
        floor: Int = 1
    ): Int {
        val chosen = if (preference > 0) preference else autoRowsFor(heightDp)
        return chosen.coerceIn(maxOf(MIN_ROWS, floor), maxOf(floor, cap))
    }

    fun resolveHomeRows(preference: Int, heightDp: Float, cap: Int = MANUAL_MAX_HOME_ROWS): Int {
        val chosen = if (preference > 0) preference else autoHomeRows(heightDp)
        return chosen.coerceIn(1, maxOf(1, cap))
    }

    // ------------------------------------------------------------------ icon size

    /**
     * Automatic icon size: the largest square that fits the cell while leaving
     * room for the label and a comfortable gap. It grows with the screen,
     * shrinks as the grid gets denser, and stays legible in either direction.
     *
     * The label under the icon plus the cell padding always stay within the
     * cell, so labels are never clipped.
     */
    fun computeIconPx(
        cellWidthDp: Float,
        cellHeightDp: Float,
        columns: Int,
        rows: Int,
        density: Float,
        minPx: Int = MIN_ICON_DP,
        maxPx: Int = MAX_ICON_DP
    ): Int {
        if (!cellWidthDp.isFinite() || !cellHeightDp.isFinite()) return minPx
        val cell = minOf(cellWidthDp, cellHeightDp)
        if (cell <= 0f) return minPx
        val padDp = cellPaddingDp(cell)
        val usableDp = cell - 2f * padDp
        if (usableDp <= 0f) return minPx

        // The label sits under the icon; keep it inside the cell.
        val labelReserve = LABEL_RESERVE_DP.coerceAtMost(usableDp * 0.45f)
        val heightForIcon = (usableDp - labelReserve).coerceAtLeast(usableDp * 0.35f)

        // Single row / single column: do not let one icon swallow the cell.
        val roomyFactor =
            (if (rows == 1) SINGLE_ROW_FACTOR else 1f) *
                (if (columns == 1) SINGLE_COLUMN_FACTOR else 1f)

        val fittedDp = minOf(usableDp, heightForIcon, ROOMY_ICON_CAP_DP) * roomyFactor
        val densitySafe = if (density > 0f) density else 1f
        val minIconPx = (minPx * densitySafe).roundToInt()
        // 手动选择很密的网格时，上限也不能超过格子本身能容纳的大小。
        val maxIconPx = (maxPx * densitySafe).roundToInt()
        val capByCellPx = kotlin.math.floor(usableDp * densitySafe).toInt()
        // Round down so icon + label + padding never overflow the cell.
        return kotlin.math.floor(fittedDp * densitySafe).toInt()
            .coerceIn(minIconPx, minOf(maxIconPx, capByCellPx.coerceAtLeast(minIconPx)))
    }

    /** Padding around the icon+label column, derived from cell size (dp). */
    fun cellPaddingDp(cellDp: Float): Float = (cellDp * 0.08f).coerceIn(4f, 16f)

    /** Row height estimate used by the recent-task sheet. */
    fun rowHeightDp(iconPx: Int, density: Float): Int {
        val d = if (density > 0f) density else 1f
        return ((iconPx / d).roundToInt() + 34).coerceAtLeast(48)
    }
}
