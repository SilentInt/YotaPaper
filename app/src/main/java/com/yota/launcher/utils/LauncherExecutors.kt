package com.yota.launcher.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局线程池，替代原先散落各处的 Thread { ... }.start()。
 *
 * 两个出口：
 *  - [io]  ：慢活（查已装应用、读写文件、root 命令）
 *  - [main]：回主线程更新 UI
 *
 * [io] 内部吞掉异常，单个任务失败不影响其它任务；但会记一条 warning，
 * 否则图标生成、二维码这类失败在真机上完全无迹可寻。
 */
object LauncherExecutors {

    private const val TAG = "LauncherExecutors"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val ioExecutor = Executors.newFixedThreadPool(4, object : ThreadFactory {
        private val seq = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "launcher-io-${seq.incrementAndGet()}").apply { isDaemon = true }
    })

    /** 丢一个慢活到后台线程；异常只记日志，不影响队列里的其它任务。 */
    fun io(block: () -> Unit) {
        ioExecutor.execute {
            runCatching(block).onFailure { e ->
                Log.w(TAG, "io task failed", e)
            }
        }
    }

    /** 把活送回主线程执行。如果本来就在主线程，直接跑，不多绕一圈。 */
    fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
