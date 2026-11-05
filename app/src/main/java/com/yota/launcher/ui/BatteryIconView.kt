package com.yota.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.yota.launcher.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 墨水屏电池图标，两种画法由设置里的「主页顶部 → 图标风格」切换。
 *
 * ## 线条版（[setBlockStyle] 传 false，默认）
 *
 * 几何照搬 EinkWallpaper 竖屏时钟顶部那颗电池（ClockRenderer.drawPortraitStatus）：
 * 外框 46x26、圆角 4、描边 3；凸点 5x10、圆角 1，和外框隔 1；电量四根竖条，
 * 宽 7、步进 9.5、左起 5、上下各收 6。
 *
 * ### 为什么所有坐标都要先化成整数像素
 *
 * 源样式是画在 720x1280 面板上的**原生像素**：外框 46px 高 26px、描边 3px，
 * 每条边都恰好压在像素格上，所以是实心黑。
 *
 * 搬到 Yota（540x960、1.5 倍密度）后，控件只有 33x16px，等比系数 0.635：
 * 描边变成 1.90px、圆角变成 2.54px，而且控件高本身可能是半像素。一条 1.9px 的线
 * 压在像素缝上，抗锯齿会把它摊成两列各 50% 的黑 —— 在 16 灰阶面板上就是一道**灰线**；
 * 圆角 2.5px 再被这层灰糊一圈，看上去就是直角。
 *
 * 所以这里不直接画 float 坐标：先把整组缩到整数像素（宽高、描边、圆角、格子全部取整），
 * 再按「描边居中」反推路径位置。描边宽为偶数时路径落在整数边界上、为奇数时落在
 * 半像素上，两种情况下描边内外沿都正好压在像素边界，抗锯齿不会摊灰；只有圆角弧线
 * 仍是平滑过渡，这是正常的。描边另外保底 2px —— 1px 线在 16 灰阶上会被直接吃掉。
 *
 * ## 实心版（[setBlockStyle] 传 true）
 *
 * 改造前那套 view_battery.xml 的造型：黑框（1.5dp 内衬换成等宽的描边，效果相同但不依赖
 * 背景色）、宽度 = 电量的连续填充条、右侧 2x4 无缝帽子。
 * 与源版唯一的差别是**外框和帽子都给了圆角** —— 源版是直角，和旁边的线条图标并排时
 * 尖角显得生硬，这里把外沿圆角定在 2.5dp（约高度的 21%），和线条版同一个比例。
 * 原实现是 FrameLayout + padding + layout_weight，做不出圆角、也没法和小尺寸的
 * 线条版共用一个控件；这里用同一套整数像素思路重画。
 */
class BatteryIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        // ---- 线条版设计空间（EinkWallpaper 竖屏时钟的原始像素值）----
        const val FRAME_W = 46f
        const val FRAME_H = 26f
        const val FRAME_R = 4f      // 路径圆角，外沿看是 4 + 描边/2
        const val STROKE = 3f

        const val NUB_GAP = 1f
        const val NUB_W = 5f
        const val NUB_H = 10f
        const val NUB_R = 1f

        const val BAR_MAX = 4
        const val BAR_W = 7f
        const val BAR_STEP = 9.5f
        const val BAR_INSET_X = 5f
        const val BAR_INSET_Y = 6f

        // 外框 + 缝隙 + 凸点
        const val DESIGN_W = FRAME_W + NUB_GAP + NUB_W   // 52
        const val DESIGN_H = FRAME_H                     // 26

        // ---- 实心版设计空间（12 高；宽度不参与设计 —— 见 drawBlock 里的说明）----
        const val BLOCK_H = 12f
        const val BLOCK_BORDER = 1.5f
        const val BLOCK_RADIUS = 2.5f   // 外沿圆角，约占高度的 21%，与线条版同比例
        const val BLOCK_NUB_W = 2f
        const val BLOCK_NUB_H = 4f

        /** 描边下限：再细在 16 灰阶面板上就看不见了 */
        const val MIN_STROKE = 2
    }

    // minSdk 17，用不上 API 23 的 Context.getColor
    @Suppress("DEPRECATION")
    private val ink = context.resources.getColor(R.color.ink)

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.FILL
    }

    private val frameRect = RectF()
    private val nubRect = RectF()

    /** 电量百分比；-1 表示还没拿到，此时只画空框（和源样式一致） */
    private var percent = -1

    /** true = 实心方框版，false = 线条四格版 */
    private var block = false

    fun setBlockStyle(block: Boolean) {
        if (block == this.block) return
        this.block = block
        invalidate()
    }

    /**
     * 只记电量、不急着重画。
     *
     * 线条版是四格 25% 一档：85% 和 99% 画出来一模一样，电量广播每掉 1% 就来一次，
     * 每次都 invalidate 就是在墨水屏上白刷一块，所以跨档才重画。
     * 实心版是连续填充条，每 1% 都不一样，必须每次都重画。
     */
    fun setPercent(pct: Int) {
        if (pct == percent) return
        val before = barsFor(percent)
        percent = pct
        if (block || barsFor(pct) != before) invalidate()
    }

    private fun barsFor(pct: Int): Int =
        if (pct in 0..100) ceil(pct / 25.0).toInt().coerceIn(0, BAR_MAX) else -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (block) drawBlock(canvas) else drawLine(canvas)
    }

    // ---------------------------------------------------------------- 线条版

    private fun drawLine(canvas: Canvas) {
        val unit = min(width / DESIGN_W, height / DESIGN_H)
        if (unit <= 0f) return

        // ---- 第一步：整组尺寸取整，居中偏移也取整，避免整块图标坐在半像素上 ----
        val totalW = round(DESIGN_W * unit).toInt()
        val totalH = round(DESIGN_H * unit).toInt()
        if (totalW <= MIN_STROKE * 2 || totalH <= MIN_STROKE * 2) return
        val left = (width - totalW) / 2
        val top = (height - totalH) / 2

        val stroke = max(MIN_STROKE, round(STROKE * unit).toInt())
        val nubW = max(2, round(NUB_W * unit).toInt())
        val nubGap = max(1, round(NUB_GAP * unit).toInt())
        val frameW = totalW - nubGap - nubW
        val frameH = totalH
        if (frameW <= stroke * 2 || frameH <= stroke * 2) return

        // ---- 第二步：描边居中反推路径，让描边的内外沿都压在像素边界上 ----
        // 圆角按外沿取整再减半个描边。下限 4px 不是为了好看，是因为 3px 的弧
        // 在这种小尺寸下只剩一个像素的拐角，看上去还是直角（源样式在 46x26 上是
        // 5.5px 外圆角，占高度 21%，等比缩小后比例对了但眼睛已经读不出弧度）
        val outerRadius = max(4, round((FRAME_R + STROKE / 2f) * unit).toInt())
            .coerceAtMost(frameH / 2)
        val pathRadius = max(0f, outerRadius - stroke / 2f)
        val half = stroke / 2f

        framePaint.strokeWidth = stroke.toFloat()
        framePaint.strokeCap = Paint.Cap.ROUND
        frameRect.set(
            left + half, top + half,
            left + frameW - half, top + frameH - half
        )
        canvas.drawRoundRect(frameRect, pathRadius, pathRadius, framePaint)

        // 正极凸点：整块贴在外框右侧，垂直居中
        val nubH = max(2, round(NUB_H * unit).toInt())
        val nubLeft = left + frameW + nubGap
        val nubTop = top + (frameH - nubH) / 2
        nubRect.set(
            nubLeft.toFloat(), nubTop.toFloat(),
            (nubLeft + nubW).toFloat(), (nubTop + nubH).toFloat()
        )
        val nubRadius = max(1f, round(NUB_R * unit))
        canvas.drawRoundRect(nubRect, nubRadius, nubRadius, fillPaint)

        if (percent !in 0..100) return

        // ---- 第三步：四格电量，矩形坐标全部取整，边界天然是实心黑 ----
        val barW = max(2, round(BAR_W * unit).toInt())
        val step = max(barW + 1, round(BAR_STEP * unit).toInt())
        val insetY = max(1, round(BAR_INSET_Y * unit).toInt())
            .coerceAtMost((frameH - MIN_STROKE) / 2)
        // 左起 5 是照源的，但小尺寸下要先把四格塞得进去
        val insetX = min(
            max(stroke + 1, round(BAR_INSET_X * unit).toInt()),
            max(1, (frameW - 3 * step - barW) / 2)
        )

        val barTop = top + insetY
        val barBottom = top + frameH - insetY
        val bars = barsFor(percent)
        for (i in 0 until bars) {
            val bx = left + insetX + i * step
            if (bx + barW > left + frameW - stroke) break
            canvas.drawRect(
                bx.toFloat(), barTop.toFloat(),
                (bx + barW).toFloat(), barBottom.toFloat(), fillPaint
            )
        }
    }

    // ---------------------------------------------------------------- 实心版

    private fun drawBlock(canvas: Canvas) {
        // 高度按 12dp 走，宽度不设设计值而是直接用控件宽度 —— 因为「框 + 帽子」的
        // 总宽在两处本来就不一样：主页那颗是 22dp（20 框 + 2 帽），控制中心那颗是
        // 24dp（22 框 + 2 帽）。用控件宽度反推框宽，两种尺寸都天然对得上。
        val unit = height / BLOCK_H
        if (unit <= 0f) return

        val totalW = width
        val totalH = round(BLOCK_H * unit).toInt()
        val top = (height - totalH) / 2
        val border = max(2, round(BLOCK_BORDER * unit).toInt())
        val nubW = max(2, round(BLOCK_NUB_W * unit).toInt())
        val nubH = max(2, round(BLOCK_NUB_H * unit).toInt())
        val left = 0
        val frameW = totalW - nubW
        val frameH = totalH
        if (frameW <= border * 2 || frameH <= border * 2) return

        // 外框：老版是「黑底 + paper 内衬」，等价于一圈 border 宽的描边，
        // 这样不依赖背景色，放在任何底色上都一样。
        // 圆角按「外沿」给：源版是直角，但和旁边的线条版并排时直角显得生硬，
        // 这里取外沿 2.5dp（约等于高度的 21%，与线条版的圆角比例一致），
        // 描边是居中画的，所以路径半径要再减半个描边。
        val half = border / 2f
        val outerRadius = max(3, round(BLOCK_RADIUS * unit).toInt())
            .coerceAtMost(frameH / 2)
        val pathRadius = max(0f, outerRadius - half)
        framePaint.strokeWidth = border.toFloat()
        framePaint.strokeCap = Paint.Cap.BUTT
        frameRect.set(
            left + half, top + half,
            left + frameW - half, top + frameH - half
        )
        canvas.drawRoundRect(frameRect, pathRadius, pathRadius, framePaint)

        // 正极帽子：贴着方框右侧，中间没有缝；同样给一点圆角，别在圆角框旁边留个尖角
        val nubLeft = left + frameW
        val nubTop = top + (frameH - nubH) / 2
        nubRect.set(
            nubLeft.toFloat(), nubTop.toFloat(),
            (nubLeft + nubW).toFloat(), (nubTop + nubH).toFloat()
        )
        val nubRadius = max(1f, round(0.8f * unit))
        canvas.drawRoundRect(nubRect, nubRadius, nubRadius, fillPaint)

        if (percent !in 0..100) return

        // 连续填充：宽度 = 电量，贴左边条走，和老版的 layout_weight 同一个算法
        val inner = frameW - border * 2
        val fillW = round(inner * percent / 100f).toInt()
        if (fillW <= 0) return
        canvas.drawRect(
            (left + border).toFloat(), (top + border).toFloat(),
            (left + border + fillW).toFloat(), (top + frameH - border).toFloat(), fillPaint
        )
    }
}
