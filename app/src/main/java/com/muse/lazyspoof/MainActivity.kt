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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import android.os.Build
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar as M3TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold as M3Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme as m3DynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme as m3DynamicLightColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight

/**
 * LazySpoof 2.0 — M3EX only (Miuix dropped). The hook engine (Java hooks + native
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
            val onForceDarkChange: (Boolean) -> Unit = {
                forceDark = it
                uiPrefs.edit().putBoolean("force_dark", it).apply()
            }
            M3Theme(forceDark = forceDark) {
                M3Screen(
                    forceDark = forceDark,
                    onForceDarkChange = onForceDarkChange
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

    // ---- Material 3 UI (M3EX only) ----

    @Composable
    private fun M3Theme(forceDark: Boolean, content: @Composable () -> Unit) {
        val context = LocalContext.current
        val dark = forceDark || isSystemInDarkTheme()
        val scheme = when {
            dark -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) m3DynamicDarkColorScheme(context) else m3DarkColorScheme()
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) m3DynamicLightColorScheme(context) else m3LightColorScheme()
        }
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(36.dp)
            ),
            content = content
        )
    }

    @Composable
    private fun M3ButtonGroup(actions: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions.forEach { (label, onClick) ->
                Button(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    M3Text(label, maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun M3SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        M3Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            content = { Column(modifier = Modifier.padding(16.dp), content = content) }
        )
    }

    @Composable
    private fun M3SectionTitle(text: String) {
        M3Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }

    @Composable
    private fun M3Field(label: String, value: String, onValueChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { M3Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun M3Screen(forceDark: Boolean, onForceDarkChange: (Boolean) -> Unit) {
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
        var scopedLoading by remember { mutableStateOf(true) }
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
                    "  •  Seen: " + ScopeReader.lastSeenCount +
                    (if (ScopeReader.lastHiddenStale > 0) "  •  Hidden stale: " + ScopeReader.lastHiddenStale else "")
                runOnUiThread {
                    scopedApps = apps
                    diag = d
                    scopedLoading = false
                }
            }
        }
        LaunchedEffect(Unit) { refreshScoped() }

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

        M3Scaffold(
            topBar = {
                M3TopAppBar(title = { M3Text("LazySpoof 2.0-m3ex1") })
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    item(key = "scope_title") { M3SectionTitle("Scoped apps") }
                    item(key = "scope") {
                        M3SectionCard {
                            Button(
                                onClick = { scopedLoading = true; refreshScoped() },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                M3Text("Refresh")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            M3Text(diag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (scopedLoading) {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                }
                            } else if (scopedApps.isEmpty()) {
                                M3Text(
                                    "No scoped apps found. Scope apps in LSPosed, then Refresh.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            scopedApps.forEachIndexed { idx, app ->
                                if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    val icon = app.icon
                                    if (icon != null) {
                                        AndroidView(
                                            factory = { ctx ->
                                                ImageView(ctx).apply {
                                                    setImageDrawable(icon)
                                                }
                                            },
                                            modifier = Modifier.size(56.dp).padding(end = 12.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        M3Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        M3Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Button(
                                        onClick = {
                                            if (workingPkg != null) return@Button
                                            quickResetApp(app) { working ->
                                                workingPkg = if (working) app.packageName else null
                                                if (!working) refreshKey++
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        M3Text(if (workingPkg == app.packageName) "Working…" else "Reset & launch")
                                    }
                                }
                            }
                        }
                    }
                    item(key = "audit_title") { M3SectionTitle("Consistency audit") }
                    item(key = "audit") {
                        val audit = remember(auditKey) { ConsistencyAudit.run() }
                        val fails = audit.count { it.level == ConsistencyAudit.Level.FAIL }
                        val warns = audit.count { it.level == ConsistencyAudit.Level.WARN }
                        val passes = audit.count { it.level == ConsistencyAudit.Level.PASS }
                        val statusColor = when {
                            fails > 0 -> MaterialTheme.colorScheme.error
                            warns > 0 -> Color(0xFFCC8800)
                            else -> Color(0xFF2E9E5B)
                        }
                        val statusText = when {
                            fails > 0 -> "⚠ " + fails + " issues to fix"
                            warns > 0 -> "Mostly consistent — " + warns + " notes"
                            else -> "Identity consistent ✅"
                        }
                        M3SectionCard {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    M3Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = statusColor)
                                    M3Text(
                                        passes.toString() + " checks passed • " + warns + " warnings • " + fails + " failures",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(onClick = { auditKey++ }) {
                                    M3Text("Re-check")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            M3Text(
                                "Checks the saved persona the module will serve to apps — not a live read from inside a scoped app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            audit.filter { it.level != ConsistencyAudit.Level.PASS }.forEach { check ->
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                M3Text(
                                    (if (check.level == ConsistencyAudit.Level.FAIL) "✕ " else "⚠ ") + check.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (check.level == ConsistencyAudit.Level.FAIL) MaterialTheme.colorScheme.error else Color(0xFFCC8800)
                                )
                                M3Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item(key = "device_title") { M3SectionTitle("Device") }
                    item(key = "device") {
                        M3SectionCard {
                            val name = currentProfile.displayName()
                            M3Text(if (name.isEmpty()) "—" else name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            M3Text(
                                currentProfile.fingerprint(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            M3ButtonGroup(
                                listOf(
                                    "Preset" to { showPresets = !showPresets },
                                    "Edit" to {
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
                                    "Android" to { showVersions = !showVersions }
                                )
                            )
                        }
                    }
                    if (showPresets) {
                        item(key = "presets") {
                            M3SectionCard {
                                val currentFp = ConfigManager.getRawProperty("ro.build.fingerprint") ?: ""
                                presets.forEachIndexed { i, preset ->
                                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    M3Card(
                                        onClick = {
                                            applyProperties(preset.toProperties())
                                            showPresets = false
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            M3Text(
                                                if (preset.fingerprint() == currentFp) "✓ " + preset.label() else preset.label(),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            M3Text(
                                                preset.fingerprint(),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (showDeviceEdit) {
                        item(key = "device_edit") {
                            M3SectionCard {
                                M3Field("Brand", editBrand) { editBrand = it }
                                M3Field("Manufacturer", editManufacturer) { editManufacturer = it }
                                M3Field("Model", editModel) { editModel = it }
                                M3Field("Name", editName) { editName = it }
                                M3Field("Device", editDevice) { editDevice = it }
                                M3Field("Board", editBoard) { editBoard = it }
                                M3Field("Hardware", editHardware) { editHardware = it }
                                M3Field("Platform", editPlatform) { editPlatform = it }
                                M3Field("Build ID", editBuildId) { editBuildId = it }
                                M3Field("Incremental", editIncremental) { editIncremental = it }
                                M3Field("Security patch", editSecurityPatch) { editSecurityPatch = it }
                                M3Field("Fingerprint", editFingerprint) { editFingerprint = it }
                                Spacer(modifier = Modifier.height(12.dp))
                                M3ButtonGroup(
                                    listOf(
                                        "Save" to {
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
                                        "Cancel" to { showDeviceEdit = false }
                                    )
                                )
                            }
                        }
                    }
                    if (showVersions) {
                        item(key = "versions") {
                            M3SectionCard {
                                val currentIdx = AndroidVersionTable.currentIndex()
                                for (i in 0 until AndroidVersionTable.size()) {
                                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    val entry = AndroidVersionTable.get(i)
                                    M3Card(
                                        onClick = {
                                            applyProperties(AndroidVersionTable.buildUpdates(entry))
                                            showVersions = false
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            M3Text(
                                                if (i == currentIdx) "✓ " + entry.displayName else entry.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            M3Text(
                                                "SDK " + entry.sdk + " • " + entry.buildId + " • " + entry.securityPatch,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "identifiers_title") { M3SectionTitle("Identifiers") }
                    item(key = "identifiers_actions") {
                        M3SectionCard {
                            M3ButtonGroup(
                                listOf(
                                    "Randomize all" to {
                                        randomizeAll()
                                        persistExecutor.execute {
                                            persistNow()
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity, "Randomized & saved", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        refreshKey++
                                    },
                                    "Save now" to {
                                        persistExecutor.execute {
                                            persistNow()
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            )
                        }
                    }
                    val grouped = identifiers.groupBy { it.first.category }
                    for ((category, rows) in grouped) {
                        item(key = "cat_$category") { M3SectionTitle(category) }
                        item(key = "card_$category") {
                            M3SectionCard {
                                rows.forEachIndexed { idx, (def, value, enabled) ->
                                    if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            M3Text(def.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            M3Text(
                                                if (value.isEmpty()) "—" else value,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = enabled,
                                            onCheckedChange = {
                                                ConfigManager.setIdentifierEnabled(def.id, it)
                                                schedulePersist()
                                                refreshKey++
                                            }
                                        )
                                    }
                                    if (editingId == def.id) {
                                        M3Field("New value", editingText) { editingText = it }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        M3ButtonGroup(
                                            listOf(
                                                "Save" to {
                                                    if (editingText.isBlank()) {
                                                        Toast.makeText(this@MainActivity, "Empty value ignored", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        ConfigManager.setIdentifierValue(def.id, editingText)
                                                        schedulePersist()
                                                        refreshKey++
                                                    }
                                                    editingId = null
                                                },
                                                "Cancel" to { editingId = null }
                                            )
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        M3ButtonGroup(
                                            listOf(
                                                "Copy" to { copyText(value, def.displayName) },
                                                "Randomize" to {
                                                    ConfigManager.setIdentifierValue(def.id, def.generator.generate())
                                                    schedulePersist()
                                                    refreshKey++
                                                },
                                                "Edit" to {
                                                    editingId = def.id
                                                    editingText = value
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "locale_header") {
                        M3SectionCard {
                            M3Card(
                                onClick = { showLocale = !showLocale },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    M3Text(
                                        if (showLocale) "▾ Timezone & language" else "▸ Timezone & language",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    M3Text(
                                        if (tzText.isBlank() && localeText.isBlank()) "Tap to expand" else (tzText + " • " + localeText),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (showLocale) item(key = "locale") {
                        M3SectionCard {
                            M3Field("Timezone (e.g. Africa/Cairo)", tzText) { tzText = it }
                            M3Field("Locale tag (e.g. en-US)", localeText) { localeText = it }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
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
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                M3Text("Apply")
                            }
                        }
                    }
                    item(key = "flags_header") {
                        M3SectionCard {
                            M3Card(
                                onClick = { showFlags = !showFlags },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    M3Text(
                                        if (showFlags) "▾ Hook flags" else "▸ Hook flags",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    M3Text("Tap to expand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (showFlags) item(key = "flags") {
                        val displaySpoof = remember(refreshKey) { ConfigManager.isDisplaySpoofEnabled() }
                        val nativeProps = remember(refreshKey) { ConfigManager.isNativePropsEnabled() }
                        val hideAccounts = remember(refreshKey) { ConfigManager.isHideAccountsEnabled() }
                        val verbose = remember(refreshKey) { ConfigManager.isVerboseLoggingEnabled() }
                        M3SectionCard {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    M3Text("Spoof display", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    M3Text("Screen size / density spoofing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = displaySpoof, onCheckedChange = { applyProperties(mapOf("hooks.spoof_display" to if (it) "1" else "0")) })
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    M3Text("Native properties", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    M3Text("Native-layer property hooks (LSPlt)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = nativeProps, onCheckedChange = { applyProperties(mapOf("hooks.native_props" to if (it) "1" else "0")) })
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    M3Text("Hide accounts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    M3Text("Hide account queries from scoped apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = hideAccounts, onCheckedChange = { applyProperties(mapOf("hooks.hide_accounts" to if (it) "1" else "0")) })
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    M3Text("Verbose logging", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    M3Text("Extra hook logs for debugging", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = verbose, onCheckedChange = { applyProperties(mapOf("debug.verbose" to if (it) "1" else "0")) })
                            }
                        }
                    }
                    item(key = "advanced_header") {
                        M3SectionCard {
                            M3Card(
                                onClick = { showAdvanced = !showAdvanced },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    M3Text(
                                        if (showAdvanced) "▾ Advanced" else "▸ Advanced",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    M3Text("Tap to expand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (showAdvanced) item(key = "advanced") {
                        M3SectionCard {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    M3Text("Force dark theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    M3Text("Off = follow system", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = forceDark, onCheckedChange = onForceDarkChange)
                            }
                        }
                    }
                    item(key = "footer") {
                        Spacer(modifier = Modifier.height(8.dp))
                        M3SectionCard {
                            M3Text("Hooks unchanged from v1.3", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            M3Text(
                                "Enable LazySpoof in LSPosed, reboot, scope your apps, then use Reset & launch here. Re-scope after reinstall: the module package is the same, so scope survives updates.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
