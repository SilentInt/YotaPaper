package com.yota.launcher.ui

import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yota.launcher.R
import com.yota.launcher.data.grid.GridSizing
import kotlin.math.abs
import kotlin.math.min

/**
 * E-ink app grid, modeled on the reference launcher's grid management:
 *
 * - The ViewGroup always keeps a stable pool of [ItemViewHolder]s sized
 *   `columns * rows`, exactly like the reference `EInkLauncherView` /
 *   `LauncherAdapter` pair. Children are inflated only when the grid size
 *   changes.
 * - Page data is only rebound into the existing holders; empty slots are
 *   cleared in place. No remove/add, no requestLayout, no invalidate is
 *   issued for data changes.
 *
 * Icon size is adaptive: unless a fixed size was passed to [configure], the
 * icon square is fitted to the current cell and grid
 * (see [GridSizing.computeIconPx]), so the same view behaves identically on a
 * 480x640 phone panel and on larger e-ink screens.
 */
class EInkLauncherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    interface OnItemInteractionListener {
        fun onItemClick(info: ResolveInfo)
        fun onItemLongClick(info: ResolveInfo)
    }

    interface OnPageChangeListener {
        fun onNextPage()
        fun onPrevPage()
    }

    private class ItemViewHolder(val itemView: View) {
        val container: ViewGroup = itemView.findViewById(R.id.itemContainer)
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val label: TextView = itemView.findViewById(R.id.label)
        val marker: TextView = itemView.findViewById(R.id.marker)
        var boundKey: String? = null
    }

    private var columns = 4
    private var rows = 5

    /** Last applied square size in px; -1 until the first layout pass. */
    private var appliedIconPx = -1
    private var appliedLabelSize = -1f

    private var apps: List<ResolveInfo?> = emptyList()
    private var selection: Set<String> = emptySet()
    private var hiddenApps: Set<String> = emptySet()
    private var manageMode = false
    private var showDividers = false
    private var itemListener: OnItemInteractionListener? = null
    private var pageListener: OnPageChangeListener? = null

    private val holders = ArrayList<ItemViewHolder>()

    private val dividerPaint = Paint().apply {
        color = 0xFFD5D5D0.toInt()
        strokeWidth = 1f
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var swipeThreshold = 60f

    init {
        // ViewGroup skips onDraw by default; the divider hairlines live there.
        setWillNotDraw(false)
    }

    /** 设置网格行列；图标大小由 [onMeasure] 根据屏幕与格子自动计算。 */
    fun configure(columns: Int, rows: Int) {
        this.columns = columns.coerceIn(GridSizing.MIN_COLUMNS, GridSizing.MAX_COLUMNS)
        this.rows = rows.coerceIn(GridSizing.MIN_ROWS, GridSizing.MAX_ROWS)
    }

    /** Icon edge in px that the grid would use for [columns] x [rows] at [widthPx] x [heightPx]. */
    fun iconPxFor(widthPx: Int, heightPx: Int, columns: Int = this.columns, rows: Int = this.rows): Int {
        val density = resources.displayMetrics.density
        return GridSizing.computeIconPx(
            cellWidthDp = if (columns > 0) widthPx.toFloat() / columns / density else 0f,
            cellHeightDp = if (rows > 0) heightPx.toFloat() / rows / density else 0f,
            columns = columns,
            rows = rows,
            density = density
        )
    }

    fun setApps(
        pageApps: List<ResolveInfo?>,
        selection: Set<String> = emptySet(),
        manageMode: Boolean = false,
        showDividers: Boolean = false,
        hidden: Set<String> = emptySet()
    ) {
        apps = pageApps
        this.selection = selection
        this.manageMode = manageMode
        this.hiddenApps = hidden
        val dividerChanged = this.showDividers != showDividers
        this.showDividers = showDividers
        resetGrid()
        if (dividerChanged) invalidate()
    }

    /**
     * Updates only selection markers. Does not touch icons, labels or layout,
     * so toggling an app in manage mode stays as cheap as possible.
     */
    fun updateSelection(selection: Set<String>) {
        this.selection = selection
        if (!manageMode) return
        for (i in holders.indices) {
            val info = apps.getOrNull(i) ?: continue
            val marker = holders[i].marker
            if (marker.visibility != View.VISIBLE) continue
            val pkg = info.activityInfo.packageName
            val text = when {
                selection.contains(pkg) -> "●"
                hiddenApps.contains(pkg) -> "隐"
                else -> "○"
            }
            if (marker.text != text) marker.text = text
            val color = if (selection.contains(pkg)) 0xFF1A1A1A.toInt() else 0xFF8A8A8A.toInt()
            if (marker.currentTextColor != color) marker.setTextColor(color)
        }
    }

    fun setOnItemInteractionListener(listener: OnItemInteractionListener?) {
        itemListener = listener
    }

    fun setOnPageChangeListener(listener: OnPageChangeListener?) {
        pageListener = listener
    }

    // ------------------------------------------------------------------ Grid

    /**
     * Reference-style reset: keep a stable holder pool of `columns * rows`.
     * Inflate only when that size changes; otherwise just rebind data.
     */
    private fun resetGrid() {
        val targetCount = columns * rows
        if (holders.size != targetCount) {
            removeAllViews()
            holders.clear()
            val inflater = LayoutInflater.from(context)
            for (i in 0 until targetCount) {
                val itemView = inflater.inflate(R.layout.item_app, this, false)
                itemView.isHapticFeedbackEnabled = false
                addView(itemView)
                holders.add(ItemViewHolder(itemView))
            }
            // The pool was rebuilt: icon metrics must be re-applied on next layout.
            appliedIconPx = -1
            appliedLabelSize = -1f
        }
        rebind()
    }

    private fun rebind() {
        for (i in holders.indices) {
            bindHolder(holders[i], apps.getOrNull(i))
        }
    }

    private fun bindHolder(holder: ItemViewHolder, info: ResolveInfo?) {
        val item = holder.itemView
        val icon = holder.icon
        val label = holder.label
        val marker = holder.marker

        if (info == null) {
            // Reference-style clear: empty slot is blank and fully transparent.
            holder.boundKey = null
            if (item.alpha != 0f) item.alpha = 0f
            if (icon.drawable != null) icon.setImageDrawable(null)
            if (icon.alpha != 1f) icon.alpha = 1f
            if (label.text?.isNotEmpty() == true) label.text = ""
            if (marker.visibility != View.GONE) marker.visibility = View.GONE
            if (item.isClickable) item.isClickable = false
            if (item.isLongClickable) item.isLongClickable = false
            item.setOnClickListener(null)
            item.setOnLongClickListener(null)
            return
        }

        if (item.alpha != 1f) item.alpha = 1f

        // 冷启动首帧只绑定 label；图标异步加载，内存/磁盘缓存命中后回填。
        val key = info.activityInfo.packageName + "/" + info.activityInfo.name
        holder.boundKey = key
        IconLoader.loadAsync(context, info) { drawable ->
            if (holder.boundKey == key && drawable != null) {
                icon.setImageDrawable(drawable)
            }
        }

        val labelText = info.loadLabel(context.packageManager)
        if (label.text != labelText) label.text = labelText

        val packageName = info.activityInfo.packageName
        val isHidden = hiddenApps.contains(packageName)
        val desiredAlpha = if (isHidden) 0.35f else 1f
        if (icon.alpha != desiredAlpha) icon.alpha = desiredAlpha

        val desiredLabelColor = if (isHidden) 0xFF8A8A8A.toInt() else 0xFF1A1A1A.toInt()
        if (label.currentTextColor != desiredLabelColor) label.setTextColor(desiredLabelColor)

        if (manageMode) {
            val markerText = when {
                selection.contains(packageName) -> "●"
                isHidden -> "隐"
                else -> "○"
            }
            val markerColor =
                if (selection.contains(packageName)) 0xFF1A1A1A.toInt() else 0xFF8A8A8A.toInt()
            if (marker.visibility != View.VISIBLE) marker.visibility = View.VISIBLE
            if (marker.text != markerText) marker.text = markerText
            if (marker.currentTextColor != markerColor) marker.setTextColor(markerColor)
            item.isClickable = true
            item.isLongClickable = false
            item.setOnClickListener { itemListener?.onItemClick(info) }
            item.setOnLongClickListener(null)
        } else {
            if (marker.visibility != View.GONE) marker.visibility = View.GONE
            item.isClickable = true
            item.isLongClickable = true
            item.setOnClickListener { itemListener?.onItemClick(info) }
            item.setOnLongClickListener {
                itemListener?.onItemLongClick(info)
                true
            }
        }
    }

    // ------------------------------------------------------------------ Adaptive metrics

    /**
     * Applies the icon square, label text size and inner padding that fit the
     * current cell. Called from layout so it survives rotation, window resizes
     * and grid changes without every caller recomputing numbers.
     */
    private fun applyAdaptiveMetrics(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0 || holders.isEmpty()) return
        val density = resources.displayMetrics.density
        val cellW = widthPx.toFloat() / columns
        val cellH = heightPx.toFloat() / rows
        val cellDp = min(cellW, cellH) / density

        val iconPx = iconPxFor(widthPx, heightPx)
        // 标签随格子缩小：大格子最多 12sp；手动选择了很密的网格时降到 7.5sp，
        // 仍保持单行可读（不换行、不截断成不可辨认）。
        val labelSizeSp = (cellDp * 0.19f).coerceIn(7.5f, 12f)
        val containerPadPx = (GridSizing.cellPaddingDp(cellDp) * density).toInt()
        val labelMarginPx = (min(6f, maxOf(2f, cellDp * 0.05f)) * density).toInt()

        val iconChanged = iconPx != appliedIconPx
        val labelChanged = labelSizeSp != appliedLabelSize
        if (!iconChanged && !labelChanged) return

        appliedIconPx = iconPx
        appliedLabelSize = labelSizeSp

        for (holder in holders) {
            val icon = holder.icon
            val lp = icon.layoutParams
            if (lp.width != iconPx || lp.height != iconPx) {
                lp.width = iconPx
                lp.height = iconPx
                icon.layoutParams = lp
            }
            holder.container.setPadding(containerPadPx, containerPadPx, containerPadPx, containerPadPx)
            val labelLp = holder.label.layoutParams as? LinearLayout.LayoutParams
            if (labelLp != null && labelLp.topMargin != labelMarginPx) {
                labelLp.topMargin = labelMarginPx
                holder.label.layoutParams = labelLp
            }
            if (holder.label.textSize != labelSizeSp) holder.label.textSize = labelSizeSp
        }
    }

    // ------------------------------------------------------------------ Layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val w = widthSize - paddingLeft - paddingRight
        val h = heightSize - paddingTop - paddingBottom
        if (w > 0 && h > 0) {
            applyAdaptiveMetrics(w, h)
            val cellW = MeasureSpec.makeMeasureSpec(w / columns, MeasureSpec.EXACTLY)
            val cellH = MeasureSpec.makeMeasureSpec(h / rows, MeasureSpec.EXACTLY)
            for (holder in holders) {
                holder.itemView.measure(cellW, cellH)
            }
        }
        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        swipeThreshold = min(w, h) / 8f
        val cellW = w / columns
        val cellH = h / rows
        for (index in holders.indices) {
            val row = index / columns
            val col = index % columns
            holders[index].itemView.layout(
                paddingLeft + col * cellW,
                paddingTop + row * cellH,
                paddingLeft + (col + 1) * cellW,
                paddingTop + (row + 1) * cellH
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            // Force a metric recomputation for the new cell size.
            appliedIconPx = -1
            requestLayout()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showDividers) return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val cellW = w / columns
        val cellH = h / rows
        for (col in 1 until columns) {
            val x = (paddingLeft + col * cellW).toFloat()
            canvas.drawLine(x, paddingTop.toFloat(), x, (paddingTop + h).toFloat(), dividerPaint)
        }
        for (row in 1 until rows) {
            val y = (paddingTop + row * cellH).toFloat()
            canvas.drawLine(paddingLeft.toFloat(), y, (paddingLeft + w).toFloat(), y, dividerPaint)
        }
    }

    // ------------------------------------------------------------------ Gesture

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - touchDownX)
                val dy = abs(ev.y - touchDownY)
                if (swipeThreshold > 0 && (dx > swipeThreshold || dy > swipeThreshold)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val adx = abs(dx)
                val ady = abs(dy)
                if (swipeThreshold > 0 && (adx > swipeThreshold || ady > swipeThreshold)) {
                    val next: Boolean
                    val axis: String
                    if (adx > ady) {
                        next = dx < 0
                        axis = if (dx < 0) "left" else "right"
                    } else {
                        next = dy < 0
                        axis = if (dy < 0) "up" else "down"
                    }
                    android.util.Log.d("EInkLauncherView",
                        "swipe: dx=$dx dy=$dy threshold=$swipeThreshold -> " +
                                "$axis ${if (next) "onNextPage" else "onPrevPage"}")
                    if (next) pageListener?.onNextPage() else pageListener?.onPrevPage()
                    return true
                } else {
                    android.util.Log.d("EInkLauncherView",
                        "swipe ignored: dx=$dx dy=$dy threshold=$swipeThreshold")
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
