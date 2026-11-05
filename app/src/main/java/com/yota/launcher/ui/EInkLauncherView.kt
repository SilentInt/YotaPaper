package com.yota.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.yota.launcher.R
import com.yota.launcher.data.AppEntry
import kotlin.math.abs
import kotlin.math.min

/**
 * E-ink app grid, modeled on the reference launcher's grid management:
 *
 * - The ViewGroup always keeps a stable pool of [ItemViewHolder]s sized
 *   `columns * rows`, exactly like the reference `EInkLauncherView` /
 *   `LauncherAdapter` pair. Children are inflated only when the grid size
 *   changes. 行数浮动的场景用 [reservePool] 把池子固定住，避免数据量跨过行
 *   边界就整池重建。
 * - Page data is only rebound into the existing holders; empty slots are
 *   cleared in place. No remove/add, no requestLayout, no invalidate is
 *   issued for data changes.
 * - 只接收 [AppEntry]：label / packageName 由数据层带出，本类不触及 PackageManager。
 */
class EInkLauncherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    companion object {
        // reservePool 的行数上限，防止误传一个很大的容量把 ViewGroup 撑爆
        private const val MAX_POOL_ROWS = 12
    }

    interface OnItemInteractionListener {
        fun onItemClick(entry: AppEntry)
        fun onItemLongClick(entry: AppEntry)
    }

    interface OnPageChangeListener {
        fun onNextPage()
        fun onPrevPage()
    }

    private class ItemViewHolder(val itemView: View) {
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val label: TextView = itemView.findViewById(R.id.label)
        val marker: TextView = itemView.findViewById(R.id.marker)
        var boundKey: String? = null
    }

    private var columns = 4
    private var rows = 5
    private var iconSizeDp = 44 // 新增：保存图标大小

    // holder 池按这个容量分配，与实际数据量无关；多出来的槽位在 bindHolder 里走空分支。
    // 最近任务这类"行数随数据量浮动"的场景靠它避免每次刷新都 removeAllViews + 重新 inflate。
    private var poolSize = 0
    private var apps: List<AppEntry?> = emptyList()
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

    // 新增：加入图标大小参数
    fun configure(columns: Int, rows: Int, iconSizeDp: Int = 44) {
        val colsChanged = this.columns != columns.coerceIn(2, 6)
        this.columns = columns.coerceIn(2, 6)
        this.rows = rows.coerceIn(1, 7)
        this.iconSizeDp = iconSizeDp
        // 显式 reservePool 过就让容量独立于 rows 浮动；但列数变了必须重算，否则
        // 池子是按旧列数切分的，reservePool 的对齐计算就失效了
        if (poolSize == 0 || colsChanged) {
            poolSize = this.columns * this.rows
        }
    }

    /**
     * 固定 holder 池容量，不随数据量变化。
     *
     * 用于行数浮动的网格：先把池子按最大可能行数建好，之后 [setApps] 只改数据、
     * 不再动池子，避免"数量跨过行边界"就整池重建。多出的槽位由 [setApps] 传空
     * 数据清成透明，占位不影响观感。
     *
     * @param capacity 池子槽位数，会被向上对齐到 columns 的整数倍。
     */
    fun reservePool(capacity: Int) {
        val cols = columns.coerceIn(2, 6)
        val slots = capacity.coerceIn(cols, cols * MAX_POOL_ROWS)
        poolSize = ((slots + cols - 1) / cols) * cols
    }

    fun setApps(
        pageApps: List<AppEntry?>,
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
            val pkg = apps.getOrNull(i)?.packageName ?: continue
            val marker = holders[i].marker
            if (marker.visibility != View.VISIBLE) continue
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
     * Reference-style reset: keep a stable holder pool sized by [poolSize]
     * (or `columns * rows` when no pool was reserved). Inflate only when that
     * size changes; otherwise just rebind data.
     */
    private fun resetGrid() {
        val targetCount = poolSize.takeIf { it > 0 } ?: (columns * rows)
        val iconPx = (iconSizeDp * context.resources.displayMetrics.density).toInt() // 计算对应像素大小

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
        }

        // 确保现有的每一个 holder 里的 icon 尺寸按照设定的 iconSizeDp 显示
        for (holder in holders) {
            val icon = holder.icon
            if (icon.layoutParams.width != iconPx || icon.layoutParams.height != iconPx) {
                icon.layoutParams = icon.layoutParams.apply {
                    width = iconPx
                    height = iconPx
                }
            }
        }
        rebind()
    }

    private fun rebind() {
        // 池子可能比可见行数大（reservePool），超出可见范围的槽位直接 GONE：
        // 既不会被测量/布局，也不参与绘制
        val visibleCount = columns * rows
        for (i in holders.indices) {
            val holder = holders[i]
            if (i >= visibleCount) {
                if (holder.itemView.visibility != View.GONE) holder.itemView.visibility = View.GONE
                holder.boundKey = null
                continue
            }
            if (holder.itemView.visibility != View.VISIBLE) holder.itemView.visibility = View.VISIBLE
            bindHolder(holder, apps.getOrNull(i))
        }
    }

    private fun bindHolder(holder: ItemViewHolder, entry: AppEntry?) {
        val item = holder.itemView
        val icon = holder.icon
        val label = holder.label
        val marker = holder.marker

        if (entry == null) {
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
        // 这里刻意不先清空旧图标：清了会多出一帧空白，墨水屏上表现为明显的闪一下。
        // 代价是换人后短暂显示上一个应用的图标，但视觉连续，反而更不容易察觉。
        holder.boundKey = entry.key
        IconLoader.loadAsync(context, entry) { drawable ->
            if (holder.boundKey == entry.key && drawable != null) {
                icon.setImageDrawable(drawable)
            }
        }

        // label 由数据层带出，不走 loadLabel
        if (label.text != entry.label) label.text = entry.label

        val packageName = entry.packageName
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
            item.setOnClickListener { itemListener?.onItemClick(entry) }
            item.setOnLongClickListener(null)
        } else {
            if (marker.visibility != View.GONE) marker.visibility = View.GONE
            item.isClickable = true
            item.isLongClickable = true
            item.setOnClickListener { itemListener?.onItemClick(entry) }
            item.setOnLongClickListener {
                itemListener?.onItemLongClick(entry)
                true
            }
        }
    }

    // ------------------------------------------------------------------ Layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val w = widthSize - paddingLeft - paddingRight
        val h = heightSize - paddingTop - paddingBottom
        if (w > 0 && h > 0) {
            val cellW = MeasureSpec.makeMeasureSpec(w / columns, MeasureSpec.EXACTLY)
            val cellH = MeasureSpec.makeMeasureSpec(h / rows, MeasureSpec.EXACTLY)
            for (holder in holders) {
                if (holder.itemView.visibility == View.GONE) continue
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
            val itemView = holders[index].itemView
            // GONE 的槽位不占位，留它停在 (0,0,0,0)
            if (itemView.visibility == View.GONE) {
                itemView.layout(0, 0, 0, 0)
                continue
            }
            val row = index / columns
            val col = index % columns
            itemView.layout(
                paddingLeft + col * cellW,
                paddingTop + row * cellH,
                paddingLeft + (col + 1) * cellW,
                paddingTop + (row + 1) * cellH
            )
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
                    // 哪个方向走得多就按哪个方向算：横向占优看左右，纵向占优看上下。
                    val next = if (adx > ady) dx < 0 else dy < 0
                    if (next) pageListener?.onNextPage() else pageListener?.onPrevPage()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
