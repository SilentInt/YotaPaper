package com.yota.launcher.xposed;

import android.view.MotionEvent;

/**
 * 每个 ViewRootImpl 一个实例，跟踪屏幕左右侧点击。
 */
final class SwipeTracker {

    static final int DIR_LEFT = -1;
    static final int DIR_RIGHT = 1;

    /** 视为点击的最大位移（像素）。 */
    static final int TAP_SLOP_PX = 80;

    private float downX;
    private float downY;
    private float totalX;
    private float totalY;
    private int direction = DIR_LEFT;
    private long tapTime;
    private boolean pending;
    /** 本次是否为点击（而非滑动）。 */
    boolean tap = false;

    /** 当前窗口是否为目标 Activity。 */
    boolean targetWindow = true;
    /** 是否已经解析过窗口所属 Activity。 */
    boolean targetChecked = false;
    /** 解析到的 Activity 完整类名，未解析到为 null。 */
    String activityName = null;

    /** 已观察到的 EPD 参数调用次数，用于节流日志。 */
    int epdCallCount = 0;

    /** 上一次真正注入动画的时间，供「同一次点击的多个参数入口」复用。 */
    long lastInjectAt = 0L;

    /**
     * 处理输入事件。viewWidth 用于把屏幕分成左右两半判定点击方向：
     * 左侧点击 = 上一页（DIR_RIGHT，动画从左缘扫到右缘）；
     * 右侧点击 = 下一页（DIR_LEFT，动画从右缘扫到左缘）。
     *
     * @return true 表示本次事件判定为一次有效点击（已置 pending）。
     */
    boolean onMotionEvent(MotionEvent ev, int viewWidth) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                totalX = 0f;
                totalY = 0f;
                pending = false;
                tap = false;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                totalX = ev.getRawX() - downX;
                totalY = ev.getRawY() - downY;
                if (Math.abs(totalX) <= TAP_SLOP_PX && Math.abs(totalY) <= TAP_SLOP_PX) {
                    // 点按：按左右半屏判定
                    tap = true;
                    float half = viewWidth > 0 ? viewWidth / 2f : 540f;
                    direction = ev.getRawX() < half ? DIR_RIGHT : DIR_LEFT;
                } else if (Math.abs(totalX) > TAP_SLOP_PX
                        && Math.abs(totalX) > Math.abs(totalY)) {
                    // 左右滑动翻页也要给方向：往左划 = 下一页（内容自右向左推），
                    // 往右划 = 上一页。之前只处理点按，滑动翻页时方向一直是上一次的
                    // 陈旧值，所以「左划右滑动画一个样」。
                    tap = false;
                    direction = totalX < 0 ? DIR_LEFT : DIR_RIGHT;
                } else {
                    // 上下滑之类，不算翻页
                    tap = false;
                    return false;
                }
                pending = true;
                tapTime = System.currentTimeMillis();
                return true;
            default:
                break;
        }
        return false;
    }

    boolean consume(int windowMs) {
        if (!pending) {
            return false;
        }
        if (System.currentTimeMillis() - tapTime > windowMs) {
            pending = false;
            return false;
        }
        pending = false;
        return true;
    }

    int direction() {
        return direction;
    }

    /** 物理按键翻页用：直接指定方向。 */
    void setDirection(int d) {
        direction = d;
    }

    /** 物理按键翻页用：当作一次有效翻页手势，置 pending 并刷新计时。 */
    void arm() {
        pending = true;
        tap = true;
        tapTime = System.currentTimeMillis();
    }

    float downX() {
        return downX;
    }

    float downY() {
        return downY;
    }

    boolean pending() {
        return pending;
    }

    boolean tap() {
        return tap;
    }

    void markWindow(boolean target, String activity) {
        targetWindow = target;
        targetChecked = true;
        activityName = activity;
    }
}
