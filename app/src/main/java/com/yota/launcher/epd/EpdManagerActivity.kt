package com.yota.launcher.epd

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yota.launcher.LauncherActivity
import com.yota.launcher.R
import com.yota.launcher.data.LauncherConfigStore
import java.util.Locale
import com.yota.launcher.utils.LauncherExecutors

/**
 * 集成在 Launcher 内的系统刷新参数管理器。
 * 数据源为本地持久化（EpdParamsStore / SharedPreferences），每次增删改
 * 都会同步写入系统 Provider，并在启动时由 Launcher 自动全部应用。
 */
class EpdManagerActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var searchEdit: EditText
    private lateinit var titleView: TextView
    private lateinit var backButton: Button
    private lateinit var autoApplySwitch: Button
    private lateinit var toggleAllButton: Button
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private lateinit var multiSelectButton: Button
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectedCountText: TextView

    private var entries: List<EpdEntry> = emptyList()
    private var currentPkg: String? = null
    private var detailShowAll = false
    private var searchQuery = ""
    private var selectionMode = false
    private val selectedKeys = HashSet<String>()

    private val appInfoCache = HashMap<String, AppInfo>()
    private val activitiesCache = HashMap<String, List<String>>()
    private var allPackages: List<String> = emptyList()

    private var pendingExportJson: String? = null
    private val requestExport = 101
    private val requestImport = 102

    // onCreate 已经加载过一次，首次 onResume 不必重复；导入返回时 onActivityResult 自己会刷
    private var firstResume = true
    private var skipResumeReload = false

    private data class AppInfo(val label: String, val icon: Drawable?)

    // ------------------------------------------------------------------ Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootView = buildUi()
        setContentView(rootView)
        searchEdit.clearFocus()
        rootView.isFocusableInTouchMode = true
        rootView.requestFocus()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        // 控制中心等处改过参数后系统 Provider 已变，本页缓存的 entries 是旧的，重读一遍
        if (firstResume) {
            firstResume = false
            return
        }
        if (skipResumeReload) {
            skipResumeReload = false
            return
        }
        loadData()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
        } else if (currentPkg != null) {
            currentPkg = null
            detailShowAll = false
            searchQuery = ""
            searchEdit.setText("")
            render()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.paper))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = paperButton("‹ 返回") { goBack() }
        backButton.visibility = View.GONE
        header.addView(backButton, LinearLayout.LayoutParams(dp(72), dp(44)).apply { marginEnd = dp(8) })
        titleView = TextView(this).apply {
            text = "EPD 刷新参数"
            setTextColor(color(R.color.ink))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        autoApplySwitch = paperButton("") { toggleAutoApply() }
        updateAutoApplySwitch()
        root.addView(autoApplySwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            bottomMargin = dp(10)
        })

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        exportButton = paperButton("导出") { exportAll() }
        importButton = paperButton("导入") { importAll() }
        multiSelectButton = paperButton("多选") { toggleSelectionMode() }
        topRow.addView(exportButton, topButtonLp())
        topRow.addView(importButton, topButtonLp())
        topRow.addView(multiSelectButton, topButtonLp())
        root.addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        selectedCountText = TextView(this).apply {
            textSize = 11f
            setTextColor(color(R.color.ink))
        }
        selectionBar.addView(selectedCountText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val selRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        selRow.addView(paperButton("设流畅") { confirmSelection("设为流畅") { e -> e.copy(mode = 1) } }, topButtonLp())
        selRow.addView(paperButton("设高画质") { confirmSelection("设为高画质") { e -> e.copy(mode = 0) } }, topButtonLp())
        selRow.addView(paperButton("设动画") { confirmSelection("设为动画") { e -> e.copy(mode = 2) } }, topButtonLp())
        selRow.addView(paperButton("重置默认") { confirmSelection("重置默认") {
            EpdEntry(it.activity, 0, 10, 2, 70, 255, 0)
        } }, topButtonLp())
        selRow.addView(paperButton("删除") { confirmSelectionDelete() }, topButtonLp())
        selectionBar.addView(selRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(selectionBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        toggleAllButton = paperButton("显示全部 activity（含未设置）") {
            detailShowAll = !detailShowAll
            render()
        }
        toggleAllButton.visibility = View.GONE
        root.addView(toggleAllButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            bottomMargin = dp(10)
        })

        searchEdit = EditText(this).apply {
            hint = "搜索应用名称 / 包名"
            textSize = 13f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.gray))
            setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, color(R.color.hairline))
                cornerRadius = dp(8).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""
                    render()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(searchEdit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            bottomMargin = dp(10)
        })

        statusText = TextView(this).apply {
            setTextColor(color(R.color.gray))
            textSize = 11f
        }
        root.addView(statusText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun topButtonLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }

    private fun paperButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(color(R.color.ink))
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, color(R.color.hairline))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }

    private fun styleDialogButton(b: Button) {
        b.setTextColor(color(R.color.ink))
        b.isAllCaps = false
        b.stateListAnimator = null
        b.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(1, color(R.color.hairline))
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun goBack() = onBackPressed()

    private fun updateAutoApplySwitch() {
        val on = LauncherConfigStore(this).autoApplyEpdParamsEnabled()
        autoApplySwitch.text = getString(R.string.settings_auto_apply_epd) + "：" + if (on) "开" else "关"
    }

    private fun toggleAutoApply() {
        val store = LauncherConfigStore(this)
        val newValue = !store.autoApplyEpdParamsEnabled()
        store.setAutoApplyEpdParamsEnabled(newValue)
        updateAutoApplySwitch()
        if (newValue) {
            toast("已开启，正在同步并应用…")
            val launcherComponent = ComponentName(this, LauncherActivity::class.java).toString()
            LauncherExecutors.io { EpdParamsStore.syncAndApply(this, launcherComponent) }
        } else {
            toast("已关闭自动同步与应用")
        }
    }

    // ------------------------------------------------------------------ Data

    private fun loadData() {
        LauncherExecutors.io {
            val list = EpdParamsStore.load(this)
            if (allPackages.isEmpty()) {
                allPackages = loadAllPackages()
            }
            val configuredPkgs = list.map { pkgOf(it.activity) }.distinct()
            (allPackages + configuredPkgs).distinct().forEach { pkg ->
                if (pkg.isNotBlank() && !appInfoCache.containsKey(pkg)) {
                    appInfoCache[pkg] = loadAppInfo(pkg)
                }
            }
            currentPkg?.let { pkg ->
                if (pkg.isNotBlank() && !activitiesCache.containsKey(pkg)) {
                    activitiesCache[pkg] = loadActivities(pkg)
                }
            }
            runOnUiThread {
                entries = list
                render()
            }
        }
    }

    private fun loadAllPackages(): List<String> {
        return runCatching {
            packageManager.getInstalledApplications(0).map { it.packageName }.distinct()
        }.getOrElse { emptyList() }
    }

    private fun loadAppInfo(pkg: String): AppInfo {
        return runCatching {
            val app = packageManager.getApplicationInfo(pkg, 0)
            AppInfo(
                label = app.loadLabel(packageManager).toString(),
                icon = app.loadIcon(packageManager)
            )
        }.getOrElse { AppInfo(pkg, null) }
    }

    private fun loadActivities(pkg: String): List<String> {
        return runCatching {
            val pi = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            pi.activities?.map { a ->
                val cls = if (a.name.startsWith(".")) pkg + a.name else a.name
                componentOf(pkg, cls)
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // ------------------------------------------------------------------ Render

    private fun render() {
        container.removeAllViews()
        val pkg = currentPkg
        updateAutoApplySwitch()
        autoApplySwitch.visibility = if (pkg == null) View.VISIBLE else View.GONE
        exportButton.visibility = if (pkg == null) View.VISIBLE else View.GONE
        importButton.visibility = if (pkg == null) View.VISIBLE else View.GONE
        multiSelectButton.text = if (selectionMode) "退出多选" else "多选"
        multiSelectButton.visibility = View.VISIBLE
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) selectedCountText.text = "已选 ${selectedKeys.size} 项"
        toggleAllButton.visibility = if (pkg != null && !selectionMode) View.VISIBLE else View.GONE

        if (pkg == null) {
            renderAppList()
        } else {
            renderAppDetail(pkg)
        }
    }

    private fun renderAppList() {
        backButton.visibility = View.VISIBLE
        searchEdit.hint = "搜索应用名称 / 包名"
        titleView.text = "EPD 刷新参数"

        val q = searchQuery.trim().lowercase(Locale.getDefault())
        val grouped = entries.groupBy { pkgOf(it.activity) }
        val configuredPkgs = grouped.keys.toSet()
        val merged = (allPackages + configuredPkgs).distinct()
            .filter { pkg -> matchesAppFilter(pkg, q) }
        val (configured, unconfigured) = merged.partition { it in configuredPkgs }
        val byLabel: (String) -> String = { pkg ->
            appInfoCache[pkg]?.label?.lowercase(Locale.getDefault()) ?: pkg
        }
        val packages = configured.sortedBy(byLabel) + unconfigured.sortedBy(byLabel)

        statusText.text = "已设置 ${configuredPkgs.size} 个应用，共显示 ${packages.size} 个应用（${entries.size} 条本地配置）"

        if (packages.isEmpty()) {
            container.addView(emptyView("没有匹配的应用"),
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120))
            return
        }
        for (pkg in packages) {
            val info = appInfoCache[pkg] ?: AppInfo(pkg, null)
            val count = grouped[pkg]?.size ?: 0
            container.addView(appRow(info, pkg, count), cardLp())
        }
    }

    private fun matchesAppFilter(pkg: String, q: String): Boolean {
        if (q.isBlank()) return true
        if (pkg.lowercase(Locale.getDefault()).contains(q)) return true
        val label = appInfoCache[pkg]?.label?.lowercase(Locale.getDefault()) ?: ""
        return label.contains(q)
    }

    private fun appRow(info: AppInfo, pkg: String, configuredCount: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener {
                if (selectionMode) toggleKey(pkg) else openApp(pkg)
            }
        }
        row.background = cardBackground()

        if (selectionMode) {
            row.addView(checkMark(pkg), LinearLayout.LayoutParams(dp(32), dp(44)).apply { marginEnd = dp(6) })
        }

        val icon = ImageView(this).apply {
            setImageDrawable(info.icon)
            setBackgroundColor(Color.rgb(0xee, 0xee, 0xee))
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = info.label
            setTextColor(color(R.color.ink))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(this).apply {
            text = pkg
            setTextColor(color(R.color.gray))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        })
        textCol.addView(TextView(this).apply {
            text = if (configuredCount > 0) "$configuredCount 项已设置" else "未设置"
            setTextColor(color(R.color.gray))
            textSize = 10f
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (!selectionMode) {
            row.addView(TextView(this).apply {
                text = "›"
                setTextColor(color(R.color.gray))
                textSize = 22f
            })
        }
        return row
    }

    private fun openApp(pkg: String) {
        currentPkg = pkg
        detailShowAll = false
        searchQuery = ""
        searchEdit.setText("")
        if (!activitiesCache.containsKey(pkg)) {
            activitiesCache[pkg] = loadActivities(pkg)
        }
        render()
    }

    private fun renderAppDetail(pkg: String) {
        backButton.visibility = View.VISIBLE
        searchEdit.hint = "搜索 activity 类名"
        val info = appInfoCache[pkg] ?: AppInfo(pkg, null)
        titleView.text = info.label

        val q = searchQuery.trim().lowercase(Locale.getDefault())
        val configuredMap = entries.filter { pkgOf(it.activity) == pkg }.associateBy { it.activity }
        val allComponents = (activitiesCache[pkg] ?: emptyList()) + configuredMap.keys

        if (selectionMode) {
            val shown = configuredMap.keys
                .sortedBy { classOf(it) }
                .filter { c ->
                    q.isBlank() ||
                        classOf(c).lowercase(Locale.getDefault()).contains(q) ||
                        c.lowercase(Locale.getDefault()).contains(q)
                }
            statusText.text = "多选模式：已选 ${selectedKeys.size} 项（本应用已设置 ${configuredMap.size} 条）"
            if (shown.isEmpty()) {
                container.addView(emptyView("该应用暂无已设置项"), LinearLayout.LayoutParams.MATCH_PARENT, dp(120))
                return
            }
            for (component in shown) {
                val entry = configuredMap[component] ?: continue
                container.addView(activityRow(component, entry), cardLp())
            }
            return
        }

        toggleAllButton.text = if (detailShowAll) "仅显示已设置" else "显示全部 activity（含未设置）"
        val shown = (if (detailShowAll) allComponents.distinct() else configuredMap.keys.toList())
            .sortedBy { classOf(it) }
            .filter { c ->
                q.isBlank() ||
                    classOf(c).lowercase(Locale.getDefault()).contains(q) ||
                    c.lowercase(Locale.getDefault()).contains(q)
            }

        val unconfiguredCount = allComponents.distinct().count { !configuredMap.containsKey(it) }
        statusText.text = if (detailShowAll)
            "已设置 ${configuredMap.size} 条，未设置 $unconfiguredCount 条（显示全部）"
        else
            "已设置 ${configuredMap.size} 条（默认只显示已设置）"

        if (shown.isEmpty()) {
            container.addView(emptyView(if (detailShowAll) "没有匹配的 activity" else "该应用暂无已设置项，可切换「显示全部 activity」"),
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120))
            return
        }
        for (component in shown) {
            val entry = configuredMap[component]
            container.addView(activityRow(component, entry), cardLp())
        }
    }

    private fun activityRow(component: String, entry: EpdEntry?): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (selectionMode && entry != null) {
                isClickable = true
                setOnClickListener { toggleKey(component) }
            }
        }
        card.background = cardBackground()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (selectionMode && entry != null) {
            head.addView(checkMark(component), LinearLayout.LayoutParams(dp(32), dp(44)).apply { marginEnd = dp(6) })
        }
        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headText.addView(TextView(this).apply {
            text = classOf(component)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.ink))
        })
        headText.addView(TextView(this).apply {
            text = component
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.gray))
        })
        head.addView(headText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)

        val summary = TextView(this).apply {
            if (entry != null) {
                text = if (entry.mode == 0) {
                    "高画质（具体参数不生效）"
                } else {
                    "${entry.modeLabel}   对比=${entry.contrast}  锐化=${entry.sharping}  " +
                        "黑伸=${entry.blackStretch}  白伸=${entry.whiteStretch}  亮度=${entry.bright}"
                }
                setTextColor(color(R.color.gray))
            } else {
                text = "未设置"
                setTextColor(color(R.color.gray))
            }
            textSize = 11f
        }
        card.addView(summary)

        if (!selectionMode) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (entry != null) {
                row.addView(paperButton("编辑") { showEditDialog(entry, entry.activity) }, smallButtonLp())
                row.addView(paperButton("删除") { softDeleteEntry(entry) }, smallButtonLp())
            } else {
                row.addView(paperButton("新增") { showEditDialog(null, null, prefillActivity = component) }, smallButtonLp())
            }
            card.addView(row)
        }
        return card
    }

    private fun checkMark(key: String): View = TextView(this).apply {
        text = if (selectedKeys.contains(key)) "☑" else "☐"
        textSize = 22f
        setTextColor(color(R.color.ink))
        gravity = Gravity.CENTER
        // toggleKey 靠这个 tag 反查勾选框；否则勾一下就得重建整页
        tag = key
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(1, color(R.color.hairline))
        cornerRadius = dp(8).toFloat()
    }

    private fun emptyView(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.gray))
        textSize = 13f
        gravity = Gravity.CENTER
    }

    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }

    private fun smallButtonLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(64), dp(40)).apply { marginStart = dp(6) }

    // ------------------------------------------------------------------ Component helpers

    private fun pkgOf(activity: String): String {
        val m = Regex("ComponentInfo\\{([^/]+)/[^}]*\\}").matchEntire(activity)
        if (m != null) return m.groupValues[1]
        val idx = activity.indexOf('/')
        return if (idx > 0) activity.substring(0, idx) else ""
    }

    private fun classOf(activity: String): String {
        val m = Regex("ComponentInfo\\{[^/]+/([^}]*)\\}").matchEntire(activity)
        if (m != null) return m.groupValues[1]
        val idx = activity.indexOf('/')
        return if (idx > 0) activity.substring(idx + 1) else activity
    }

    private fun componentOf(pkg: String, cls: String) = "ComponentInfo{$pkg/$cls}"

    // ------------------------------------------------------------------ Selection

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        selectedKeys.clear()
        render()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedKeys.clear()
        render()
    }

    private fun toggleKey(key: String) {
        // 只翻勾选框和计数，不调 render()：列表是全部已装应用一次性铺开的，
        // 重建一次要重造上千个 View 并触发整屏重刷
        val checked = if (selectedKeys.contains(key)) {
            selectedKeys.remove(key)
            false
        } else {
            selectedKeys.add(key)
            true
        }
        container.findViewWithTag<TextView>(key)?.text = if (checked) "☑" else "☐"
        selectedCountText.text = "已选 ${selectedKeys.size} 项"
    }

    private fun selectedEntries(): List<EpdEntry> {
        val pkg = currentPkg
        return if (pkg == null) {
            entries.filter { selectedKeys.contains(pkgOf(it.activity)) }
        } else {
            entries.filter { selectedKeys.contains(it.activity) }
        }
    }

    private fun confirmSelection(title: String, transform: (EpdEntry) -> EpdEntry) {
        val targets = selectedEntries()
        if (targets.isEmpty()) {
            toast("请先选择项目")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("确定对选中的 ${targets.size} 条记录执行「$title」吗？")
            .setPositiveButton("执行") { _, _ -> applySelection(targets) { transform(it) } }
            .setNegativeButton("取消", null)
            .show()
            .apply {
                getButton(AlertDialog.BUTTON_POSITIVE)?.let { styleDialogButton(it) }
                getButton(AlertDialog.BUTTON_NEGATIVE)?.let { styleDialogButton(it) }
            }
    }

    private fun confirmSelectionDelete() {
        val targets = selectedEntries()
        if (targets.isEmpty()) {
            toast("请先选择项目")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除（本地移除并占位符化系统行）选中的 ${targets.size} 条记录吗？")
            .setPositiveButton("执行") { _, _ -> applySelection(targets) { null } }
            .setNegativeButton("取消", null)
            .show()
            .apply {
                getButton(AlertDialog.BUTTON_POSITIVE)?.let { styleDialogButton(it) }
                getButton(AlertDialog.BUTTON_NEGATIVE)?.let { styleDialogButton(it) }
            }
    }

    private fun applySelection(targets: List<EpdEntry>, transform: (EpdEntry) -> EpdEntry?) {
        LauncherExecutors.io {
            var ok = 0
            val newList = entries.toMutableList()
            for (e in targets) {
                val t = transform(e)
                val success = if (t == null) {
                    // 删除：无论 Provider 行是否存在（可能已是占位符），本地都要移除。
                    EpdParamsStore.softDeleteInProvider(this, e.activity)
                    newList.removeAll { it.activity == e.activity }
                    true
                } else if (t.mode == 2) {
                    // 动画：不动系统 Provider 里已有的模式行，只在缺失时补一行高画质。
                    EpdParamsStore.ensureDisplayModeRow(this, t.activity)
                    newList.replaceAll { if (it.activity == e.activity) t else it }
                    true
                } else {
                    if (t.activity != e.activity) {
                        EpdParamsStore.softDeleteInProvider(this, e.activity)
                    }
                    if (EpdParamsStore.applyToProvider(this, t)) {
                        newList.replaceAll { if (it.activity == e.activity) t else it }
                        true
                    } else false
                }
                if (success) ok++
            }
            EpdParamsStore.save(this, newList)
            runOnUiThread {
                toast("已处理 $ok/${targets.size} 条")
                selectionMode = false
                selectedKeys.clear()
                loadData()
            }
        }
    }

    private fun softDeleteEntry(e: EpdEntry) {
        LauncherExecutors.io {
            val providerOk = EpdParamsStore.softDeleteInProvider(this, e.activity)
            EpdParamsStore.save(this, entries.filter { it.activity != e.activity })
            runOnUiThread {
                toast(if (providerOk) "已删除（占位符）：${e.activity}" else "本地已移除（Provider 行不存在或已是占位符）")
                loadData()
            }
        }
    }

    // ------------------------------------------------------------------ Export / import

    private fun exportAll() {
        val visible = entries
        if (visible.isEmpty()) {
            toast("没有可导出的条目")
            return
        }
        pendingExportJson = exportJson(visible)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "epd_params_export.json")
        }
        startActivityForResult(intent, requestExport)
    }

    private fun importAll() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, requestImport)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            // 导出不改数据、导入自己会刷新，都不必让 resume 再整页重建一遍
            requestExport -> {
                writeExport(uri)
                skipResumeReload = true
            }
            requestImport -> {
                readImport(uri)
                skipResumeReload = true
            }
        }
    }

    private fun writeExport(uri: Uri) {
        val json = pendingExportJson ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            toast("已导出 ${entries.size} 条")
        }.onFailure { toast("导出失败：${it.message}") }
        pendingExportJson = null
    }

    private fun readImport(uri: Uri) {
        LauncherExecutors.io {
            val result = runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val list = parseJson(text)
                val newList = entries.toMutableList()
                var added = 0
                var updated = 0
                for (e in list) {
                    if (e.activity.isBlank() || e.isNeutralized) continue
                    if (EpdParamsStore.applyToProvider(this, e)) {
                        val idx = newList.indexOfFirst { it.activity == e.activity }
                        if (idx >= 0) {
                            newList[idx] = e
                            updated++
                        } else {
                            newList.add(e)
                            added++
                        }
                    }
                }
                EpdParamsStore.save(this, newList)
                "导入完成：新增 $added，更新 $updated，总 ${list.size} 条"
            }.getOrElse { "导入失败：${it.message}" }
            runOnUiThread {
                toast(result)
                loadData()
            }
        }
    }

    private fun exportJson(items: List<EpdEntry>): String {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    private fun parseJson(text: String): List<EpdEntry> {
        val arr = org.json.JSONArray(text)
        val out = ArrayList<EpdEntry>()
        for (i in 0 until arr.length()) out.add(EpdEntry.fromJson(arr.getJSONObject(i)))
        return out
    }

    // ------------------------------------------------------------------ Edit dialog

    private fun showEditDialog(existing: EpdEntry?, oldActivity: String?, prefillActivity: String? = null) {
        val isEdit = existing != null
        val entry = existing ?: EpdEntry(
            activity = prefillActivity ?: "",
            mode = 0,
            contrast = 10,
            sharping = 2,
            blackStretch = 70,
            whiteStretch = 255,
            bright = 0
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val activityEdit = EditText(this).apply {
            hint = "ComponentInfo{pkg/activity}"
            setText(entry.activity)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
        }
        content.addView(activityEdit, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@EpdManagerActivity).apply {
                text = "高画质"
                id = 1
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.ink))
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(RadioButton(this@EpdManagerActivity).apply {
                text = "流畅"
                id = 2
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.ink))
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(RadioButton(this@EpdManagerActivity).apply {
                text = "动画"
                id = 3
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.ink))
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (entry.mode) {
                1 -> check(2)
                2 -> check(3)
                else -> check(1)
            }
        }
        content.addView(modeGroup, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        data class Stepper(
            val name: String,
            val min: Int,
            val max: Int,
            var value: Int,
            val valueEdit: EditText
        )

        fun numberEdit(value: Int): EditText = EditText(this).apply {
            setText(value.toString())
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(color(R.color.ink))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, color(R.color.hairline))
                cornerRadius = dp(8).toFloat()
            }
        }

        val imageSteppers = listOf(
            Stepper("对比度", 0, 32, entry.contrast, numberEdit(entry.contrast)),
            Stepper("锐化", 0, 16, entry.sharping, numberEdit(entry.sharping)),
            Stepper("黑拉伸", 0, 125, entry.blackStretch, numberEdit(entry.blackStretch)),
            Stepper("白拉伸", 0, 255, entry.whiteStretch, numberEdit(entry.whiteStretch)),
            Stepper("亮度", 0, 125, entry.bright, numberEdit(entry.bright))
        )

        val animWindowMsInit = EpdParamsStore.loadAnimWindowMs(this)
        val animFrameCountInit = EpdParamsStore.loadAnimFrameCount(this)
        // 「清残影间隔」已下线：高画质（dithering=2）本身残影就很少，
        // 再按页数插全屏重刷只会每几页突兀地闪一次黑。
        val animSteppers = listOf(
            Stepper("注入有效窗口(ms)", 200, 3000, animWindowMsInit, numberEdit(animWindowMsInit)),
            Stepper("动画帧数", 3, 60, animFrameCountInit, numberEdit(animFrameCountInit))
        )

        fun readValue(s: Stepper): Int = s.valueEdit.text.toString().trim().toIntOrNull() ?: s.value

        fun refresh(s: Stepper) {
            s.value = s.value.coerceIn(s.min, s.max)
            s.valueEdit.setText(s.value.toString())
            s.valueEdit.setSelection(s.valueEdit.text.length)
        }

        fun attachWatcher(s: Stepper) {
            s.valueEdit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) {
                    val v = e?.toString()?.trim()?.toIntOrNull()
                    if (v != null) s.value = v.coerceIn(s.min, s.max)
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        fun addStepperRow(container: LinearLayout, s: Stepper) {
            attachWatcher(s)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this).apply {
                text = "${s.name} ${s.min}–${s.max}"
                textSize = 12f
                setTextColor(color(R.color.ink))
            }, LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(paperButton("−") {
                s.value = (readValue(s) - 1).coerceAtLeast(s.min)
                refresh(s)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            row.addView(s.valueEdit, LinearLayout.LayoutParams(dp(64), dp(44)))
            row.addView(paperButton("＋") {
                s.value = (readValue(s) + 1).coerceAtMost(s.max)
                refresh(s)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            container.addView(row)
        }

        val imageParamsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (entry.mode == 1) View.VISIBLE else View.GONE
        }
        imageSteppers.forEach { addStepperRow(imageParamsContainer, it) }
        content.addView(imageParamsContainer)

        val animParamsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (entry.mode == 2) View.VISIBLE else View.GONE
        }
        animSteppers.forEach { addStepperRow(animParamsContainer, it) }

        // 条带几何不再单独选：条带数直接由上面的「动画帧数」决定
        // （15 段 = 系统阅读器同款 48px 竖条；越小越粗）。方向由点击位置决定。
        fun styleOption(id: Int, title: String, sub: String): RadioButton =
            RadioButton(this).apply {
                this.id = id
                text = "$title\n$sub"
                textSize = 13f
                setTextColor(color(R.color.ink))
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }

        // 波形档位。EpdUpdateParams 没有独立 waveform 字段，波形只由 updateType 决定：
        // 0 = DU（HAL waveform 8，不刷黑），4 = FULL/GC（整屏刷黑）。
        animParamsContainer.addView(TextView(this).apply {
            text = "刷新波形"
            textSize = 12f
            setTextColor(color(R.color.ink))
            setPadding(0, dp(8), 0, dp(2))
        })
        // 波形是逐条目保存的：编辑哪一个条目就显示/保存哪一个条目的波形，
        // 不能读全局 anim_wave，否则改 A 会把 B 的档位带跑。
        val animWaveInit = EpdParamsStore.animWaveFor(this, oldActivity ?: entry.activity)
        val animWaveGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            // 两档都走灰度波形（updateType=3），差别只在 dithering：
            //   抖动开 = 灰度过渡，条带边缘平滑；
            //   抖动关 = 没有过渡，条带扫过时是纯黑/纯白硬边，也就是「黑条带」。
            // 早先「黑条带」档写的是 updateType=4（整屏重刷），而 4 是整屏 GC，
            // HAL 不会为它跑 drawAnimation —— 那一档根本没有动画，已改掉。
            addView(styleOption(24, "平滑翻页",
                    "灰度过渡，条带边缘平滑，残影少，日常推荐"))
            addView(styleOption(23, "黑闪翻页",
                    "关掉灰度过渡，条带扫过时是黑白硬边，观感更利落"))
            when (animWaveInit) {
                EpdParamsStore.ANIM_WAVE_FULL_DITHER -> check(23)
                else -> check(24)
            }
        }
        animParamsContainer.addView(animWaveGroup)

        // 「由系统进程注入」不再做成选项：按波形自动路由。
        //   DU 档 → system_server 写（进程内写 DU 不出动画，实测）
        //   FULL 档 → 进程内写（system_server 再写一次会叠成两遍动画）
        // 见 MainHook.reloadPrefs()。
        content.addView(animParamsContainer)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            imageParamsContainer.visibility = if (checkedId == 2) View.VISIBLE else View.GONE
            animParamsContainer.visibility = if (checkedId == 3) View.VISIBLE else View.GONE
        }

        val scroll = ScrollView(this).apply { addView(content) }

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "编辑刷新参数" else "新增刷新参数")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val activity = activityEdit.text.toString().trim()
                if (activity.isBlank()) {
                    toast("activity 不能为空")
                    return@setPositiveButton
                }
                val selectedMode = when (modeGroup.checkedRadioButtonId) {
                    2 -> 1
                    3 -> 2
                    else -> 0
                }
                val newEntry = entry.copy(
                    activity = activity,
                    mode = selectedMode,
                    contrast = if (selectedMode == 1) readValue(imageSteppers[0]).coerceIn(imageSteppers[0].min, imageSteppers[0].max) else entry.contrast,
                    sharping = if (selectedMode == 1) readValue(imageSteppers[1]).coerceIn(imageSteppers[1].min, imageSteppers[1].max) else entry.sharping,
                    blackStretch = if (selectedMode == 1) readValue(imageSteppers[2]).coerceIn(imageSteppers[2].min, imageSteppers[2].max) else entry.blackStretch,
                    whiteStretch = if (selectedMode == 1) readValue(imageSteppers[3]).coerceIn(imageSteppers[3].min, imageSteppers[3].max) else entry.whiteStretch,
                    bright = if (selectedMode == 1) readValue(imageSteppers[4]).coerceIn(imageSteppers[4].min, imageSteppers[4].max) else entry.bright
                )
                val animWindowMs = readValue(animSteppers[0]).coerceIn(animSteppers[0].min, animSteppers[0].max)
                val animFrameCount = readValue(animSteppers[1]).coerceIn(animSteppers[1].min, animSteppers[1].max)
                // 条带数完全由「动画帧数」决定，不再有独立的风格选择
                val animStyle = EpdParamsStore.ANIM_STYLE_CUSTOM
                val animWave = when (animWaveGroup.checkedRadioButtonId) {
                    23 -> EpdParamsStore.ANIM_WAVE_FULL_DITHER
                    else -> EpdParamsStore.ANIM_WAVE_FOLLOW
                }
                LauncherExecutors.io {
                    // 统一入口：本地条目 + Provider 档位 + 动画参数一次写完，
                    // 和控制中心卡片共用同一套动作，避免两边再漂移。
                    val ok = EpdParamsStore.setMode(
                        this, newEntry,
                        if (isEdit) oldActivity else null,
                        animWindowMs, animFrameCount, animStyle, animWave
                    )
                    runOnUiThread {
                        toast(if (ok) "已保存" else "保存失败")
                        loadData()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .apply {
                getButton(AlertDialog.BUTTON_POSITIVE)?.let { styleDialogButton(it) }
                getButton(AlertDialog.BUTTON_NEGATIVE)?.let { styleDialogButton(it) }
            }
    }

    // ------------------------------------------------------------------ Utils

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    @Suppress("DEPRECATION")
    private fun color(resId: Int): Int = resources.getColor(resId)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
