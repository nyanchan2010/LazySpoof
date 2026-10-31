package com.muse.lazyspoof

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.muse.lazyspoof.ui.AndroidVersionTable
import com.muse.lazyspoof.ui.DevicePresets
import com.muse.lazyspoof.ui.DeviceProfile
import com.muse.lazyspoof.ui.IdentifierRegistry
import com.muse.lazyspoof.utils.ConfigManager
import com.muse.lazyspoof.utils.ConsistencyAudit
import com.muse.lazyspoof.utils.RootShell
import com.muse.lazyspoof.utils.ScopeReader
import com.muse.lazyspoof.utils.XposedServiceBridge
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * LazySpoof 2.0 — Miuix rebuild. The hook engine (Java hooks + native
 * LSPlt lib) is unchanged from upstream v1.3 + the lazy1–lazy7 fixes;
 * only this UI layer is new. Scope listing still reads the daemon
 * first (no root), QuickReset still runs pm clear + 800ms + a
 * main-thread launch with FLAG_INCLUDE_STOPPED_PACKAGES, and the
 * manifest still declares QUERY_ALL_PACKAGES so the PackageManager
 * calls can actually see the scoped apps.
 */
class MainActivity : ComponentActivity() {

    private lateinit var configFile: File
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spoof-persist").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val scopeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spoof-scope")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configFile = File(filesDir, "device_profile.conf")
        ensureConfigFile()
        ConfigManager.reload()
        migrateLegacyDefaults()
        populateMissingValues()
        XposedServiceBridge.init(this, Runnable {
            persistExecutor.execute { publishIfWritable() }
        })
        setContent {
            val uiPrefs = getSharedPreferences("ui", MODE_PRIVATE)
            var forceDark by remember {
                mutableStateOf(uiPrefs.getBoolean("force_dark", false))
            }
            val colors = when {
                forceDark -> darkColorScheme()
                isSystemInDarkTheme() -> darkColorScheme()
                else -> lightColorScheme()
            }
            MiuixTheme(colors = colors) {
                Screen(
                    forceDark = forceDark,
                    onForceDarkChange = {
                        forceDark = it
                        uiPrefs.edit().putBoolean("force_dark", it).apply()
                    }
                )
            }
        }
    }

    // ---- config plumbing (same semantics as the old View UI) ----

    private fun ensureConfigFile() {
        if (configFile.exists()) return
        try {
            FileOutputStream(configFile).use {
                it.write(ConfigManager.getDefaultConfigText().toByteArray())
                it.flush()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun migrateLegacyDefaults() {
        if ("1" == ConfigManager.getRawProperty("_defaults_v13")) return
        val updates = ConfigManager.getLegacyDefaultUpdates()
        updates["_defaults_v13"] = "1"
        ConfigManager.setProperties(updates)
    }

    private fun populateMissingValues() {
        var dirty = false
        for (d in IdentifierRegistry.all()) {
            val value = ConfigManager.getIdentifierValue(d.id)
            if (value.isNullOrEmpty()) {
                ConfigManager.setIdentifierValue(d.id, d.generator.generate())
                dirty = true
            }
        }
        if (dirty) {
            try {
                ConfigManager.saveConfig(configFile)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun schedulePersist() {
        persistExecutor.execute { persistNow() }
    }

    private fun persistNow() {
        try {
            ConfigManager.saveConfig(configFile)
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val editor = prefs.edit()
        for ((k, v) in ConfigManager.getRawProperties()) {
            editor.putString(k, v)
        }
        editor.apply()
        makePrefsWorldReadable()
        publishIfWritable()
    }

    private fun publishIfWritable() {
        if (XposedServiceBridge.isServiceWritable()) {
            ConfigManager.publishToRemotePreferences()
        }
    }

    private fun makePrefsWorldReadable() {
        try {
            val dataDir = File(applicationInfo.dataDir)
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "config.xml")
            if (prefsFile.exists()) prefsFile.setReadable(true, false)
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false)
                prefsDir.setReadable(true, false)
            }
            dataDir.setExecutable(true, false)
        } catch (ignored: Throwable) {
        }
    }

    private fun randomizeAll() {
        for (d in IdentifierRegistry.all()) {
            ConfigManager.setIdentifierValue(d.id, d.generator.generate())
        }
    }

    private fun copyText(text: String, label: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // ---- launch (SpoofMyDevice sequence, kept verbatim in behavior) ----

    private fun tryLaunchPrimary(pkg: String): Boolean {
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                )
                startActivity(launch)
                return true
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun tryLaunchMainActivity(pkg: String): Boolean {
        try {
            val main = Intent(Intent.ACTION_MAIN)
            main.setPackage(pkg)
            val resolves = packageManager.queryIntentActivities(main, 0)
            if (!resolves.isNullOrEmpty()) {
                val a = resolves[0].activityInfo
                val explicit = Intent(Intent.ACTION_MAIN)
                explicit.setClassName(pkg, a.name)
                explicit.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                )
                startActivity(explicit)
                return true
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun tryLaunchRoot(pkg: String): Boolean {
        try {
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            val activities = info.activities
            if (!activities.isNullOrEmpty()) {
                var activity = activities[0].name
                if (activity != null && activity.startsWith(".")) {
                    activity = pkg + activity
                }
                val out = RootShell.run("am start -n $pkg/$activity", 8000)
                if (out != null && !out.lowercase().contains("error")) {
                    return true
                }
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun quickResetApp(
        app: ScopeReader.ScopedApp,
        onWorkingChange: (Boolean) -> Unit
    ) {
        onWorkingChange(true)
        scopeExecutor.execute {
            randomizeAll()
            persistNow()
            var clearError: String? = null
            val clearOut = RootShell.run("pm clear " + app.packageName, 30000)
            if (clearOut == null) {
                clearError = "No root: could not clear app data"
            } else if (!clearOut.lowercase().contains("success")) {
                clearError = "Clear failed: ${clearOut.trim()}"
            }
            try {
                Thread.sleep(800)
            } catch (ignored: InterruptedException) {
            }
            val error = clearError
            runOnUiThread {
                onWorkingChange(false)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                if (tryLaunchPrimary(app.packageName)) return@runOnUiThread
                if (tryLaunchMainActivity(app.packageName)) return@runOnUiThread
                scopeExecutor.execute {
                    if (!tryLaunchRoot(app.packageName)) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Reset done, but this app has no activity to open — launch it yourself",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    // ---- Miuix UI ----

    @Composable
    private fun SubtleDivider() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.5.dp)
                .background(Color.Gray.copy(alpha = 0.25f))
        )
    }

    @Composable
    private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(text = label, fontSize = 12.sp, color = Color.Gray)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
            )
        }
    }

    @Composable
    private fun Screen(forceDark: Boolean, onForceDarkChange: (Boolean) -> Unit) {
        val context = LocalContext.current
        var refreshKey by remember { mutableStateOf(0) }
        var auditKey by remember { mutableStateOf(0) }
        var showPresets by remember { mutableStateOf(false) }
        var showDeviceEdit by remember { mutableStateOf(false) }
        var showVersions by remember { mutableStateOf(false) }
        var showLocale by remember { mutableStateOf(false) }
        var showFlags by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }
        var scopedApps by remember { mutableStateOf<List<ScopeReader.ScopedApp>>(emptyList()) }
        var diag by remember { mutableStateOf("Loading scoped apps…") }
        var workingPkg by remember { mutableStateOf<String?>(null) }
        var editingId by remember { mutableStateOf<String?>(null) }
        var editingText by remember { mutableStateOf("") }

        val presets = remember { DevicePresets.load(context) }
        val currentProfile = remember(refreshKey) { DeviceProfile.fromConfig() }
        val identifiers = remember(refreshKey) {
            IdentifierRegistry.all().map { d ->
                Triple(d, ConfigManager.getIdentifierValue(d.id) ?: "", ConfigManager.isIdentifierEnabled(d.id))
            }
        }

        fun refreshScoped() {
            scopeExecutor.execute {
                val apps = ScopeReader.load(this@MainActivity)
                val d = "Root: " + (if (ScopeReader.lastRootOk) "yes" else "no") +
                    "  •  Daemon scope: " + ScopeReader.lastDaemonScopeRows +
                    "  •  DB rows: " + ScopeReader.lastDbScopeRows +
                    "  •  Seen: " + ScopeReader.lastSeenCount
                runOnUiThread {
                    scopedApps = apps
                    diag = d
                }
            }
        }
        LaunchedEffect(Unit) { refreshScoped() }

        // Device-edit fields, seeded when the section opens.
        var editBrand by remember { mutableStateOf("") }
        var editManufacturer by remember { mutableStateOf("") }
        var editModel by remember { mutableStateOf("") }
        var editName by remember { mutableStateOf("") }
        var editDevice by remember { mutableStateOf("") }
        var editBoard by remember { mutableStateOf("") }
        var editHardware by remember { mutableStateOf("") }
        var editPlatform by remember { mutableStateOf("") }
        var editBuildId by remember { mutableStateOf("") }
        var editIncremental by remember { mutableStateOf("") }
        var editSecurityPatch by remember { mutableStateOf("") }
        var editFingerprint by remember { mutableStateOf("") }
        var tzText by remember { mutableStateOf(ConfigManager.getRawProperty("persist.sys.timezone") ?: "") }
        var localeText by remember {
            mutableStateOf(ConfigManager.getRawProperty("persist.sys.locale") ?: "")
        }

        fun applyProperties(updates: Map<String, String>) {
            if (updates.isEmpty()) return
            ConfigManager.setProperties(updates)
            schedulePersist()
            refreshKey++
        }

        Scaffold(
            topBar = { TopAppBar(title = "LazySpoof 2.0-miuix5") }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp
                )
            ) {
                item(key = "scope_title") { SmallTitle(text = "SCOPED APPS") }
                item(key = "scope") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "Refresh",
                                onClick = { refreshScoped() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(text = diag, fontSize = 12.sp, color = Color.Gray)
                        }
                        if (scopedApps.isEmpty()) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    text = "No scoped apps found. Scope apps in LSPosed, then Refresh.",
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        scopedApps.forEachIndexed { idx, app ->
                            if (idx > 0) SubtleDivider()
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val icon = app.icon
                                if (icon != null) {
                                    AndroidView(
                                        factory = { ctx ->
                                            ImageView(ctx).apply {
                                                setImageDrawable(icon)
                                            }
                                        },
                                        modifier = Modifier.size(48.dp).padding(end = 8.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = app.label, fontSize = 14.sp)
                                    Text(text = app.packageName, fontSize = 12.sp, color = Color.Gray)
                                }
                                TextButton(
                                    text = if (workingPkg == app.packageName) "Working…" else "Reset & launch",
                                    onClick = {
                                        if (workingPkg != null) return@TextButton
                                        quickResetApp(app) { working ->
                                            workingPkg = if (working) app.packageName else null
                                            if (!working) refreshKey++
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                item(key = "audit_title") { SmallTitle(text = "CONSISTENCY AUDIT") }
                item(key = "audit") {
                    val audit = remember(auditKey) { ConsistencyAudit.run() }
                    val fails = audit.count { it.level == ConsistencyAudit.Level.FAIL }
                    val warns = audit.count { it.level == ConsistencyAudit.Level.WARN }
                    val passes = audit.count { it.level == ConsistencyAudit.Level.PASS }
                    val statusColor = when {
                        fails > 0 -> Color.Red
                        warns > 0 -> Color(0xFFCC8800)
                        else -> Color(0xFF2E9E5B)
                    }
                    val statusText = when {
                        fails > 0 -> "⚠ " + fails + " مشكلة لازم تتصلح"
                        warns > 0 -> "متسقة أساساً — " + warns + " ملاحظات"
                        else -> "الهوية متسقة ✅"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = statusText, fontSize = 15.sp, color = statusColor)
                                Text(text = passes.toString() + " فحص ناجح • " + warns + " تحذير • " + fails + " خطأ", fontSize = 12.sp, color = Color.Gray)
                            }
                            TextButton(
                                text = "إعادة الفحص",
                                onClick = { auditKey++ }
                            )
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(
                                text = "يفحص الشخصية المحفوظة اللي الموديول هيقدمها للتطبيقات — مش قراءة حية من جوه تطبيق scoped.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        audit.filter { it.level != ConsistencyAudit.Level.PASS }.forEach { check ->
                            SubtleDivider()
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    text = (if (check.level == ConsistencyAudit.Level.FAIL) "✕ " else "⚠ ") + check.title,
                                    fontSize = 14.sp,
                                    color = if (check.level == ConsistencyAudit.Level.FAIL) Color.Red else Color(0xFFCC8800)
                                )
                                Text(text = check.detail, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
                item(key = "device_title") { SmallTitle(text = "DEVICE") }
                item(key = "device") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        val name = currentProfile.displayName()
                        BasicComponent(
                            title = if (name.isEmpty()) "—" else name,
                            summary = currentProfile.fingerprint(),
                            onClick = {}
                        )
                        SubtleDivider()
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "Preset",
                                onClick = { showPresets = !showPresets },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = "Edit",
                                onClick = {
                                    val c = DeviceProfile.fromConfig()
                                    editBrand = c.brand
                                    editManufacturer = c.manufacturer
                                    editModel = c.model
                                    editName = c.name
                                    editDevice = c.device
                                    editBoard = c.board
                                    editHardware = c.hardware
                                    editPlatform = c.platform
                                    editBuildId = c.buildId
                                    editIncremental = c.incremental
                                    editSecurityPatch = c.securityPatch
                                    editFingerprint = c.fingerprint
                                    showDeviceEdit = !showDeviceEdit
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = "Android",
                                onClick = { showVersions = !showVersions },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                if (showPresets) {
                    item(key = "presets") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            val currentFp = ConfigManager.getRawProperty("ro.build.fingerprint") ?: ""
                            presets.forEachIndexed { i, preset ->
                                if (i > 0) SubtleDivider()
                                BasicComponent(
                                    title = if (preset.fingerprint() == currentFp) "✓ ${preset.label()}" else preset.label(),
                                    summary = preset.fingerprint(),
                                    onClick = {
                                        applyProperties(preset.toProperties())
                                        showPresets = false
                                    }
                                )
                            }
                        }
                    }
                }
                if (showDeviceEdit) {
                    item(key = "device_edit") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Field("Brand", editBrand) { editBrand = it }
                            Field("Manufacturer", editManufacturer) { editManufacturer = it }
                            Field("Model", editModel) { editModel = it }
                            Field("Name", editName) { editName = it }
                            Field("Device", editDevice) { editDevice = it }
                            Field("Board", editBoard) { editBoard = it }
                            Field("Hardware", editHardware) { editHardware = it }
                            Field("Platform", editPlatform) { editPlatform = it }
                            Field("Build ID", editBuildId) { editBuildId = it }
                            Field("Incremental", editIncremental) { editIncremental = it }
                            Field("Security patch", editSecurityPatch) { editSecurityPatch = it }
                            Field("Fingerprint", editFingerprint) { editFingerprint = it }
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    text = "Save",
                                    onClick = {
                                        val before = DeviceProfile.fromConfig()
                                        val edited = DeviceProfile.fromConfig()
                                        edited.brand = editBrand
                                        edited.manufacturer = editManufacturer
                                        edited.model = editModel
                                        edited.name = editName
                                        edited.device = editDevice
                                        edited.board = editBoard
                                        edited.hardware = editHardware
                                        edited.platform = editPlatform
                                        edited.buildId = editBuildId
                                        edited.incremental = editIncremental
                                        edited.securityPatch = editSecurityPatch
                                        edited.fingerprint = editFingerprint.replace("\\s".toRegex(), "")
                                        applyProperties(DeviceProfile.changedProperties(before, edited))
                                        showDeviceEdit = false
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "Cancel",
                                    onClick = { showDeviceEdit = false },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                if (showVersions) {
                    item(key = "versions") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            val currentIdx = AndroidVersionTable.currentIndex()
                            for (i in 0 until AndroidVersionTable.size()) {
                                if (i > 0) SubtleDivider()
                                val entry = AndroidVersionTable.get(i)
                                BasicComponent(
                                    title = if (i == currentIdx) "✓ ${entry.displayName}" else entry.displayName,
                                    summary = "SDK ${entry.sdk} • ${entry.buildId} • ${entry.securityPatch}",
                                    onClick = {
                                        applyProperties(AndroidVersionTable.buildUpdates(entry))
                                        showVersions = false
                                    }
                                )
                            }
                        }
                    }
                }
                item(key = "identifiers_title") { SmallTitle(text = "IDENTIFIERS") }
                item(key = "identifiers_actions") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "Randomize all",
                                onClick = {
                                    randomizeAll()
                                    persistExecutor.execute {
                                        persistNow()
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Randomized & saved", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    refreshKey++
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = "Save now",
                                onClick = {
                                    persistExecutor.execute {
                                        persistNow()
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                val grouped = identifiers.groupBy { it.first.category }
                for ((category, rows) in grouped) {
                    item(key = "cat_$category") { SmallTitle(text = category.uppercase()) }
                    item(key = "card_$category") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Column {
                                rows.forEachIndexed { idx, (def, value, enabled) ->
                                    if (idx > 0) SubtleDivider()
                                    SuperSwitch(
                                        title = def.displayName,
                                        summary = if (value.isEmpty()) "—" else value,
                                        checked = enabled,
                                        onCheckedChange = {
                                            ConfigManager.setIdentifierEnabled(def.id, it)
                                            schedulePersist()
                                            refreshKey++
                                        }
                                    )
                                    if (editingId == def.id) {
                                        Field("New value", editingText) { editingText = it }
                                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                text = "Save",
                                                onClick = {
                                                    if (editingText.isBlank()) {
                                                        Toast.makeText(this@MainActivity, "Empty value ignored", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        ConfigManager.setIdentifierValue(def.id, editingText)
                                                        schedulePersist()
                                                        refreshKey++
                                                    }
                                                    editingId = null
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                text = "Cancel",
                                                onClick = { editingId = null },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    } else {
                                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                text = "Copy",
                                                onClick = { copyText(value, def.displayName) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                text = "Randomize",
                                                onClick = {
                                                    ConfigManager.setIdentifierValue(def.id, def.generator.generate())
                                                    schedulePersist()
                                                    refreshKey++
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                text = "Edit",
                                                onClick = {
                                                    editingId = def.id
                                                    editingText = value
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "locale_header") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicComponent(
                            title = if (showLocale) "▾ Timezone & language" else "▸ Timezone & language",
                            summary = if (tzText.isBlank() && localeText.isBlank()) "Tap to expand" else (tzText + " • " + localeText),
                            onClick = { showLocale = !showLocale }
                        )
                    }
                }
                if (showLocale) item(key = "locale") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Field("Timezone (e.g. Africa/Cairo)", tzText) { tzText = it }
                        Field("Locale tag (e.g. en-US)", localeText) { localeText = it }
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "Apply",
                                onClick = {
                                    val updates = HashMap<String, String>()
                                    if (tzText.isNotBlank()) {
                                        updates["persist.sys.timezone"] = tzText.trim()
                                        updates["_tz_seeded"] = "1"
                                    }
                                    if (localeText.isNotBlank()) {
                                        val tag = localeText.trim()
                                        val dash = tag.indexOf('-')
                                        updates["locale.language"] = if (dash < 0) tag else tag.substring(0, dash)
                                        updates["locale.country"] = if (dash < 0) "" else tag.substring(dash + 1)
                                        updates["persist.sys.locale"] = tag
                                        updates["_locale_seeded"] = "1"
                                    }
                                    applyProperties(updates)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                item(key = "flags_header") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicComponent(
                            title = if (showFlags) "▾ Hook flags" else "▸ Hook flags",
                            summary = "Tap to expand",
                            onClick = { showFlags = !showFlags }
                        )
                    }
                }
                if (showFlags) item(key = "flags") {
                    val displaySpoof = remember(refreshKey) { ConfigManager.isDisplaySpoofEnabled() }
                    val nativeProps = remember(refreshKey) { ConfigManager.isNativePropsEnabled() }
                    val hideAccounts = remember(refreshKey) { ConfigManager.isHideAccountsEnabled() }
                    val verbose = remember(refreshKey) { ConfigManager.isVerboseLoggingEnabled() }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        SuperSwitch(
                            title = "Spoof display",
                            summary = "Screen size / density spoofing",
                            checked = displaySpoof,
                            onCheckedChange = {
                                applyProperties(mapOf("hooks.spoof_display" to if (it) "1" else "0"))
                            }
                        )
                        SubtleDivider()
                        SuperSwitch(
                            title = "Native properties",
                            summary = "Native-layer property hooks (LSPlt)",
                            checked = nativeProps,
                            onCheckedChange = {
                                applyProperties(mapOf("hooks.native_props" to if (it) "1" else "0"))
                            }
                        )
                        SubtleDivider()
                        SuperSwitch(
                            title = "Hide accounts",
                            summary = "Hide account queries from scoped apps",
                            checked = hideAccounts,
                            onCheckedChange = {
                                applyProperties(mapOf("hooks.hide_accounts" to if (it) "1" else "0"))
                            }
                        )
                        SubtleDivider()
                        SuperSwitch(
                            title = "Verbose logging",
                            summary = "Extra hook logs for debugging",
                            checked = verbose,
                            onCheckedChange = {
                                applyProperties(mapOf("debug.verbose" to if (it) "1" else "0"))
                            }
                        )
                    }
                }
                item(key = "advanced_header") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicComponent(
                            title = if (showAdvanced) "▾ Advanced" else "▸ Advanced",
                            summary = "Tap to expand",
                            onClick = { showAdvanced = !showAdvanced }
                        )
                    }
                }
                if (showAdvanced) item(key = "advanced") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        SuperSwitch(
                            title = "Force dark theme",
                            summary = "Off = follow system",
                            checked = forceDark,
                            onCheckedChange = onForceDarkChange
                        )
                    }
                }
                item(key = "footer") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicComponent(
                            title = "Hooks unchanged from v1.3",
                            summary = "Enable LazySpoof in LSPosed, reboot, scope your apps, then use Reset & launch here. Re-scope after reinstall: the module package is the same, so scope survives updates.",
                            onClick = {}
                        )
                    }
                }
            }
        }
    }
}
