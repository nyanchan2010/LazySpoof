package com.muse.lazyspoof.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the apps in this module's LSPosed scope, so the user never has
 * to pick them a second time inside LazySpoof.
 *
 * Sources, in order (merged, de-duplicated):
 * 1. Root: the LSPosed daemon's scope database. The daemon stores scope in a
 *    SQLite `scope` table keyed by the module's row in `modules`; the DB
 *    file is /data/adb/lspd/config/modules_config.db on BOTH classic
 *    LSPosed (ConfigFileManager.dbPath) and the JingMatrix/Vector fork
 *    (FileSystem.dbPath) — NOT a *.db file directly under /data/adb/lspd,
 *    which is why v1.4-lazy1 found nothing: it listed the top-level dir
 *    only. We copy it (plus its -wal/-shm sidecars, so a WAL-mode daemon
 *    cannot give us a stale copy) to our cache dir with root, query it
 *    read-only with Android's SQLite API, then delete the copies. The
 *    daemon's own DB is never written to.
 * 2. Root: the Vector/LSPosed CLI (`/data/adb/lspd/cli scope list <pkg>`).
 *    Its output format is not stable across forks, so we only accept tokens
 *    that are actually installed packages.
 * 3. Hook-seen registry: every time the module's hooks load inside a scoped
 *    app, MainHook records that package through ScopeSeenProvider. This
 *    needs no root and works wherever the DB/CLI paths are unknown, but an
 *    app only appears after it has run at least once with the module on.
 *
 * Everything here is best-effort: a source that fails is skipped, and the
 * caller shows the merged result plus an honest empty state when nothing
 * was found. Must be called off the main thread (root + SQLite I/O).
 */
public final class ScopeReader {

    /** SharedPreferences file the provider writes; the UI reads it directly. */
    public static final String SEEN_PREFS = "scope_seen";

    /** Last-load diagnostics, shown in the Scoped apps card (never a crash). */
    public static volatile boolean lastRootOk = false;
    /** True when a daemon DB file was opened at all (not a scope verdict). */
    public static volatile boolean lastDbFound = false;
    /** Scope rows for THIS module in the daemon DB, before any filtering. */
    public static volatile int lastDbScopeRows = 0;
    /** Scope entries from the daemon itself (IXposedService.getScope). */
    public static volatile int lastDaemonScopeRows = 0;
    public static volatile int lastSeenCount = 0;

    /** Exact daemon DB paths, tried first (classic + Vector fork agree). */
    private static final String[] DB_FILES = {
            "/data/adb/lspd/config/modules_config.db",
    };
    private static final String[] DB_DIRS = {
            "/data/adb/lspd",
            "/data/adb/vector",
            "/data/misc/lspd",
            "/data/system/lspd",
    };
    private static final String[] CLI_PATHS = {
            "/data/adb/lspd/cli",
            "/data/adb/vector/cli",
    };

    private static final Pattern PACKAGE_TOKEN =
            Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    public static final class ScopedApp {
        public final String packageName;
        public final String label;
        public final Drawable icon;

        ScopedApp(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private ScopeReader() {}

    public static List<ScopedApp> load(Context context) {
        lastRootOk = false;
        lastDbFound = false;
        lastDbScopeRows = 0;
        lastDaemonScopeRows = 0;
        lastSeenCount = 0;
        LinkedHashMap<String, Boolean> found = new LinkedHashMap<>();
        java.util.HashSet<String> dbSourced = new java.util.HashSet<>();
        // 0. Daemon scope via the service binder — NO root needed, NO
        // target launch needed. This is the primary source now; the root
        // DB/CLI paths below are only fallbacks for when the binder has
        // not arrived yet. (Root is still needed for `pm clear`, just
        // never for listing scope.)
        try {
            List<String> daemonPkgs = XposedServiceBridge.getScope();
            lastDaemonScopeRows = daemonPkgs.size();
            for (String pkg : daemonPkgs) {
                found.put(pkg, true);
                dbSourced.add(pkg);
            }
        } catch (Throwable ignored) {
        }
        // Root check is only diagnostic here (clear needs root; listing
        // does not), so it must never block the daemon result above.
        try {
            lastRootOk = RootShell.isRootAvailable();
        } catch (Throwable ignored) {
        }
        List<String> dbPkgs = readScopeFromRootDb(context);
        lastDbScopeRows = dbPkgs.size();
        for (String pkg : dbPkgs) {
            found.put(pkg, true);
            dbSourced.add(pkg);
        }
        for (String pkg : readScopeFromCli(context)) found.put(pkg, true);
        List<String> seen = readSeenPackages(context);
        lastSeenCount = seen.size();
        for (String pkg : seen) found.put(pkg, true);

        PackageManager pm = context.getPackageManager();
        String own = context.getPackageName();
        TreeMap<String, ScopedApp> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String pkg : found.keySet()) {
            if (pkg == null || pkg.equals(own) || pkg.equals("system")) continue;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                String label = String.valueOf(pm.getApplicationLabel(info));
                Drawable icon;
                try {
                    icon = pm.getApplicationIcon(info);
                } catch (Throwable ignored) {
                    icon = null;
                }
                byLabel.put(label + "\n" + pkg, new ScopedApp(pkg, label, icon));
            } catch (PackageManager.NameNotFoundException ignored) {
                // A DB scope entry that PackageManager cannot resolve for
                // this user (e.g. the "android" framework pseudo-package, or
                // an app installed under another user) is still shown, with
                // its package name as the label, so scope is never silently
                // invisible again. Seen/CLI entries stay filtered: those
                // must be real installed apps, or they are stale.
                if (dbSourced.contains(pkg)) {
                    byLabel.put(pkg + "\n" + pkg, new ScopedApp(pkg, pkg, null));
                }
            }
        }
        return new ArrayList<>(byLabel.values());
    }

    /** Packages the hooks have actually run in, via ScopeSeenProvider. */
    public static List<String> readSeenPackages(Context context) {
        List<String> out = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
            for (String key : prefs.getAll().keySet()) {
                if (key != null && PACKAGE_TOKEN.matcher(key).matches()) out.add(key);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void recordSeen(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(packageName, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    // ---- Root: daemon database ------------------------------------------

    private static List<String> readScopeFromRootDb(Context context) {
        List<String> out = new ArrayList<>();
        try {
            File cacheDir = new File(context.getCacheDir(), "lspd");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File localDb = new File(cacheDir, "scope.db");
            String own = context.getPackageName();
            // 1. Exact known path first. If it opened, its scope table is
            // the truth — even when empty — so do NOT fall through to
            // stale backups (modules_config_lsposed.db) that could show
            // scope the user has since removed.
            for (String remote : DB_FILES) {
                List<String> fromDb = tryCopyAndQuery(remote, localDb, own);
                if (lastDbFound) return fromDb;
            }
            // 2. Recursive scan of the daemon dirs, only when the known
            // path could not be opened at all (a fork that moved the DB).
            for (String dir : DB_DIRS) {
                String listing = RootShell.run(
                        "find " + dir + " -maxdepth 3 -name '*.db' 2>/dev/null", 8000);
                if (listing == null) continue;
                for (String remote : listing.split("\\s+")) {
                    if (remote.isEmpty() || remote.endsWith("magisk.db")) continue;
                    List<String> fromDb = tryCopyAndQuery(remote, localDb, own);
                    if (lastDbFound) return fromDb;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Copy a remote DB (plus WAL sidecars) out with root and query it. */
    private static List<String> tryCopyAndQuery(String remote, File localDb, String own) {
        List<String> empty = new ArrayList<>();
        try {
            String local = localDb.getAbsolutePath();
            // rm first so a failed copy can never leave a stale DB behind;
            // trailing `true` keeps the exit code 0 when a sidecar is absent.
            String copy = RootShell.run(
                    "rm -f " + local + " " + local + "-wal " + local + "-shm; "
                            + "cp " + remote + " " + local + "; "
                            + "cp " + remote + "-wal " + local + "-wal 2>/dev/null; "
                            + "cp " + remote + "-shm " + local + "-shm 2>/dev/null; "
                            + "chmod 644 " + local + " " + local + "-wal "
                            + local + "-shm 2>/dev/null; true",
                    8000);
            if (copy == null || !localDb.exists()) return empty;
            return queryScopeDb(localDb, own);
        } catch (Throwable ignored) {
            return empty;
        }
    }

    private static List<String> queryScopeDb(File dbFile, String ownPackage) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            lastDbFound = true;
            // ONLY the scope table, joined to this module's row in modules.
            // v1.4-lazy2's generic fallback scanned every *pkg* column,
            // including configs.module_pkg_name — the daemon stores a
            // module's own RemotePreferences there, so it "found" our own
            // package, load() filtered it out, and the list stayed empty
            // behind a lying "Scope DB: found" line. Never guess from
            // other tables again: no scope rows means no scope.
            Cursor c = null;
            try {
                c = db.rawQuery(
                        "SELECT app_pkg_name FROM scope WHERE mid IN "
                                + "(SELECT mid FROM modules WHERE module_pkg_name=?)",
                        new String[]{ownPackage});
                while (c != null && c.moveToNext()) {
                    String pkg = c.getString(0);
                    if (pkg != null && !pkg.isEmpty()) out.add(pkg);
                }
            } finally {
                if (c != null) c.close();
            }
        } catch (Throwable ignored) {
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
            // Never leave another app's daemon DB lying around in cache.
            try { dbFile.delete(); } catch (Throwable ignored) {}
            try { new File(dbFile.getAbsolutePath() + "-wal").delete(); } catch (Throwable ignored) {}
            try { new File(dbFile.getAbsolutePath() + "-shm").delete(); } catch (Throwable ignored) {}
        }
        return out;
    }

    // ---- Root: CLI -------------------------------------------------------

    private static List<String> readScopeFromCli(Context context) {
        List<String> out = new ArrayList<>();
        String own = context.getPackageName();
        PackageManager pm = context.getPackageManager();
        for (String cli : CLI_PATHS) {
            String result = RootShell.run(cli + " scope list " + own + " 2>/dev/null", 8000);
            if (result == null) continue;
            Matcher m = PACKAGE_TOKEN.matcher(result);
            while (m.find()) {
                String pkg = m.group();
                if (pkg.equals(own)) continue;
                try {
                    pm.getApplicationInfo(pkg, 0);
                    if (!out.contains(pkg)) out.add(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // Token from help text or a stale entry, not an installed app.
                }
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }
}
