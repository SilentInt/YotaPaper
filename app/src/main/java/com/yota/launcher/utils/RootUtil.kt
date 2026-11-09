package com.yota.launcher.utils

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RootUtil {

    // 在工具类加载时配置全局 Shell
    init {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }

    // 只缓存「有 Root」：拿不到时（用户尚未在 Magisk 允许）下次仍需重试
    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * 检查当前是否具有 Root 权限（Shell 是否以 root 身份运行）
     */
    fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val available = try {
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            Log.e("RootUtil", "isRootAvailable failed", e)
            false
        }
        if (available) cachedRootAvailable = true
        return available
    }

    fun forceStopPackage(packageName: String): Boolean {
        return forceStopPackages(listOf(packageName))
    }

    /**
     * 使用 Libsu 全局长连接 Root Shell 批量强行停止应用。
     *
     * 注意：libsu 主 Shell 内部是串行队列（SerialExecutorService），
     * 这里多条命令也是依次执行的，适合数量少/无需并发的场景。
     */
    fun forceStopPackages(packageNames: List<String>): Boolean {
        if (packageNames.isEmpty()) return true

        // 【关键修复】显式获取 Shell 并强制检查是否为 Root。
        // 这一步会强制阻塞并唤起 Magisk 授权弹窗。
        val shell = Shell.getShell()
        if (!shell.isRoot) {
            Log.e("RootUtil", "Root permission denied or not available.")
            return false // 用户拒绝，或者被 KernelSU 静默拒绝
        }

        val commands = packageNames.map { "am force-stop $it" }.toTypedArray()

        return try {
            val result = Shell.cmd(*commands).exec()
            result.isSuccess
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 通过 root 枚举 [packageNames] 中“当前仍有进程存活”的应用。
     *
     * 为什么不用 ActivityManager.getRunningAppProcesses：Android 5.0 起该 API 对
     * 第三方应用只返回自身进程，无法判断其它应用是否在跑，会把所有包都判成“空闲”
     * 从而一个都不 force-stop（曾导致“杀不掉”回归）。这里用单条 root 命令 ps
     * 枚举进程名（完整包名，进程名 = pkg 或 pkg:子进程），一次 su 调用、精确且快。
     *
     * 若 root 枚举失败（壳异常/输出为空/解析不出进程），返回全部包名 ——
     * 退回“全杀”，保证旧行为永不失效。
     */
    fun runningProcessPackages(packageNames: List<String>): List<String> {
        if (packageNames.isEmpty()) return emptyList()

        val shell = Shell.getShell()
        if (!shell.isRoot) return packageNames

        // 单条 root 命令 "ps"（Android toolbox ps 的 NAME 列 = 完整包名，不截断）。
        // 不依赖 /proc 循环，避免多层引号转义。
        val out = runCatching { Shell.cmd("ps").exec().getOut() }.getOrNull()
        if (out.isNullOrEmpty()) return packageNames

        // 每行形如 "USER PID PPID VSIZE RSS WCHAN PC NAME"，进程名是最后一个字段
        val procs = out.mapNotNull { line ->
            line.trim()
                .split(Regex("\\s+"))
                .lastOrNull()
                ?.takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch in "._:-" } }
        }
        if (procs.isEmpty()) return packageNames

        return packageNames.filter { pkg ->
            procs.any { it == pkg || it.startsWith("$pkg:") }
        }
    }

    /**
     * 并发强停（用于“最近任务清理”提速）。
     *
     * 只应在 [forceStopPackages] 完成过一次授权（Shell.getShell().isRoot）后调用：
     * libsu 单壳内命令是串行的，多命令并不会并行，因此这里改用少量独立
     * `su -c am force-stop` 进程并发执行；每批最多 [concurrency] 个。
     * 串行等待时间 ≈ 单批（一次 force-stop + 一次 su 拉起）。
     */
    fun forceStopPackagesParallel(packageNames: List<String>, concurrency: Int = 3): Boolean {
        if (packageNames.isEmpty()) return true
        if (concurrency <= 1 || packageNames.size <= 1) {
            return forceStopPackages(packageNames)
        }

        val shell = Shell.getShell()
        if (!shell.isRoot) {
            Log.e("RootUtil", "Root permission denied or not available.")
            return false
        }

        val pool = Executors.newFixedThreadPool(minOf(concurrency, packageNames.size))
        return try {
            val futures = packageNames.map { pkg ->
                pool.submit(Callable {
                    runCatching {
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg"))
                        p.waitFor() == 0
                    }.getOrDefault(false)
                })
            }
            futures.all {
                runCatching { it.get(30, TimeUnit.SECONDS) }.getOrDefault(false)
            }
        } finally {
            pool.shutdown()
        }
    }
}
