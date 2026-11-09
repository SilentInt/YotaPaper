package com.yota.launcher.utils

import android.util.Log

/**
 * 跟 Activity 生命周期绑定的任务作用域：[cancel] 之后未回调的结果全部丢弃，
 * 避免界面销毁后仍在更新 UI 或持有 Activity 引用。
 */
class TaskScope {

    @Volatile
    private var active = true

    /** 界面销毁时调用，之后回主线程的结果一律丢弃。 */
    fun cancel() {
        active = false
    }

    /** 后台执行 [work]，完成后回主线程把结果交给 [after]。 */
    fun <T> worker(work: () -> T, after: (T) -> Unit) {
        LauncherExecutors.io {
            // 异常在这里被捕获，外层 LauncherExecutors 看不到，所以日志得在这儿记
            val result = runCatching(work).onFailure { e ->
                Log.w(TAG, "worker task failed", e)
            }.getOrNull() ?: return@io
            LauncherExecutors.main {
                if (active) after(result)
            }
        }
    }

    /** 只在后台执行，不需要回主线程（预热、同步等）。 */
    fun io(work: () -> Unit) {
        LauncherExecutors.io(work)
    }

    private companion object {
        private const val TAG = "TaskScope"
    }
}
