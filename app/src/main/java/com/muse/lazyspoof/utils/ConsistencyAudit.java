package com.muse.lazyspoof.utils;

import com.muse.lazyspoof.ui.IdentifierRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Identity-consistency audit for the saved spoof persona.
 *
 * <p>Reads the same persisted config the hooks serve (ConfigManager), so the
 * audit answers "what will a scoped app see?" — not a live read from inside a
 * scoped app, which this process cannot do. It never touches hook code.
 *
 * <p>Severity is deliberate: FAIL = a value no real device of this persona
 * could ever carry (or two fields that directly contradict each other).
 * WARN = plausible on a real device (dual SIM, roaming, hand edits) but worth
 * a second look. Anything the audit cannot verify (e.g. IMEI TAC against a
 * model database — no TAC database is bundled) is simply not claimed.
 */
public final class ConsistencyAudit {

    public enum Level { PASS, WARN, FAIL }

    public static final class Check {
        public final Level level;
        public final String title;
        public final String detail;

        public Check(Level level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }
    }

    private static final Map<String, String> MCC_COUNTRY = new HashMap<>();
    private static final Map<String, String> TZ_COUNTRY = new HashMap<>();
    private static final Map<String, Integer> RELEASE_SDK = new HashMap<>();

    static {
        MCC_COUNTRY.put("602", "EG");
        MCC_COUNTRY.put("310", "US");
        MCC_COUNTRY.put("311", "US");
        MCC_COUNTRY.put("312", "US");
        MCC_COUNTRY.put("313", "US");
        MCC_COUNTRY.put("316", "US");
        MCC_COUNTRY.put("262", "DE");
        MCC_COUNTRY.put("208", "FR");
        MCC_COUNTRY.put("234", "GB");
        MCC_COUNTRY.put("235", "GB");
        MCC_COUNTRY.put("440", "JP");
        MCC_COUNTRY.put("441", "JP");
        MCC_COUNTRY.put("460", "CN");
        MCC_COUNTRY.put("461", "CN");
        MCC_COUNTRY.put("222", "IT");
        MCC_COUNTRY.put("214", "ES");
        MCC_COUNTRY.put("250", "RU");
        MCC_COUNTRY.put("724", "BR");
        MCC_COUNTRY.put("505", "AU");
        MCC_COUNTRY.put("216", "HU");
        MCC_COUNTRY.put("204", "NL");
        MCC_COUNTRY.put("206", "BE");
        MCC_COUNTRY.put("219", "HR");
        MCC_COUNTRY.put("230", "CZ");

        TZ_COUNTRY.put("Africa/Cairo", "EG");
        TZ_COUNTRY.put("Asia/Dubai", "AE");
        TZ_COUNTRY.put("Asia/Riyadh", "SA");
        TZ_COUNTRY.put("Asia/Jerusalem", "IL");
        TZ_COUNTRY.put("Europe/London", "GB");
        TZ_COUNTRY.put("Europe/Berlin", "DE");
        TZ_COUNTRY.put("Europe/Paris", "FR");
        TZ_COUNTRY.put("Europe/Rome", "IT");
        TZ_COUNTRY.put("Europe/Madrid", "ES");
        TZ_COUNTRY.put("America/New_York", "US");
        TZ_COUNTRY.put("America/Chicago", "US");
        TZ_COUNTRY.put("America/Los_Angeles", "US");
        TZ_COUNTRY.put("America/Sao_Paulo", "BR");
        TZ_COUNTRY.put("Asia/Tokyo", "JP");
        TZ_COUNTRY.put("Asia/Shanghai", "CN");
        TZ_COUNTRY.put("Asia/Kolkata", "IN");
        TZ_COUNTRY.put("Australia/Sydney", "AU");
        TZ_COUNTRY.put("America/Toronto", "CA");
        TZ_COUNTRY.put("America/Mexico_City", "MX");
        TZ_COUNTRY.put("Europe/Moscow", "RU");

        RELEASE_SDK.put("16", 36);
        RELEASE_SDK.put("15", 35);
        RELEASE_SDK.put("14", 34);
        RELEASE_SDK.put("13", 33);
        RELEASE_SDK.put("12", 32); // 12L; plain 12 (31) accepted below
        RELEASE_SDK.put("11", 30);
        RELEASE_SDK.put("10", 29);
        RELEASE_SDK.put("9", 28);
    }

    public static List<Check> run() {
        List<Check> out = new ArrayList<>();
        checkFingerprint(out);
        checkBrand(out);
        checkReleaseSdk(out);
        checkIdentifiers(out);
        checkSimCoherence(out);
        checkDisplay(out);
        checkCpu(out);
        checkMemory(out);
        checkUserAgent(out);
        return out;
    }

    // ---- fingerprint vs Build fields ----

    private static void checkFingerprint(List<Check> out) {
        String fp = ConfigManager.getBuildFingerprint();
        if (fp == null || fp.isEmpty()) {
            out.add(new Check(Level.WARN, "Fingerprint",
                    "No build fingerprint set — apps fall back to the real one."));
            return;
        }
        // brand/product/device:version-release/id/incremental:type/tags
        // e.g. google/panther/panther:13/TQ3A.230805.001/10316578:user/release-keys
        // NOTE: there are TWO colons — one after device, one between
        // incremental and type. Splitting only on "/" leaves "10316578:user"
        // as one segment; it must be split on ":" too.
        try {
            int colon = fp.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException();
            String[] left = fp.substring(0, colon).split("/");
            String[] right = fp.substring(colon + 1).split("/");
            if (left.length != 3 || right.length < 4) throw new IllegalArgumentException();
            String[] incType = right[2].split(":");
            if (incType.length != 2) throw new IllegalArgumentException();
            List<String> bad = new ArrayList<>();
            cmp(bad, "brand", left[0], ConfigManager.getBuildBrand());
            cmp(bad, "product", left[1], ConfigManager.getBuildProduct());
            cmp(bad, "device", left[2], ConfigManager.getBuildDevice());
            cmp(bad, "version", right[0], ConfigManager.getBuildVersionRelease());
            cmp(bad, "build id", right[1], ConfigManager.getBuildId());
            cmp(bad, "incremental", incType[0], ConfigManager.getBuildVersionIncremental());
            cmp(bad, "build type", incType[1], ConfigManager.getBuildType());
            cmp(bad, "build tags", right[3], ConfigManager.getBuildTags());
            if (bad.isEmpty()) {
                out.add(new Check(Level.PASS, "Fingerprint",
                        "Fingerprint matches brand, product, device, version and build fields."));
            } else {
                out.add(new Check(Level.FAIL, "Fingerprint mismatch",
                        "Fingerprint disagrees with the Build fields on: " + join(bad) + "."));
            }
        } catch (Exception e) {
            out.add(new Check(Level.WARN, "Fingerprint",
                    "Fingerprint does not parse as brand/product/device:version-release/id/incremental:type/tags."));
        }
    }

    private static void cmp(List<String> bad, String name, String fromFp, String fromBuild) {
        if (fromFp == null || fromFp.isEmpty() || fromBuild == null || fromBuild.isEmpty()) return;
        if (!fromFp.equals(fromBuild)) bad.add(name + " (fp=" + fromFp + ", build=" + fromBuild + ")");
    }

    private static void checkBrand(List<Check> out) {
        String brand = ConfigManager.getBuildBrand();
        String manu = ConfigManager.getBuildManufacturer();
        if (brand == null || brand.isEmpty() || manu == null || manu.isEmpty()) return;
        if (brand.equalsIgnoreCase(manu)) {
            out.add(new Check(Level.PASS, "Brand / manufacturer",
                    "Brand and manufacturer agree (" + brand + ")."));
        } else {
            out.add(new Check(Level.FAIL, "Brand / manufacturer mismatch",
                    "Brand=" + brand + " but manufacturer=" + manu + " — no real device splits these."));
        }
    }

    private static void checkReleaseSdk(List<Check> out) {
        String release = ConfigManager.getBuildVersionRelease();
        if (release == null || release.isEmpty()) return;
        String major = release.split("\\.")[0];
        Integer expected = RELEASE_SDK.get(major);
        if (expected == null) return;
        int sdk = ConfigManager.getBuildVersionSdk();
        boolean ok = sdk == expected || ("12".equals(major) && sdk == 31);
        if (ok) {
            out.add(new Check(Level.PASS, "Android version / SDK",
                    "Android " + release + " with SDK " + sdk + " is a real pairing."));
        } else {
            out.add(new Check(Level.FAIL, "Android version / SDK mismatch",
                    "Android " + release + " should be SDK " + expected + ", not " + sdk + "."));
        }
        String patch = ConfigManager.getBuildVersionSecurityPatch();
        if (patch != null && !patch.isEmpty() && !patch.matches("\\d{4}-\\d{2}-\\d{2}")) {
            out.add(new Check(Level.WARN, "Security patch format",
                    "Patch level '" + patch + "' is not a YYYY-MM-DD date."));
        }
    }

    // ---- identifiers ----

    private static void checkIdentifiers(List<Check> out) {
        List<String> disabled = new ArrayList<>();
        List<String> badFormat = new ArrayList<>();
        List<String> badLuhn = new ArrayList<>();
        for (IdentifierRegistry.Definition d : IdentifierRegistry.all()) {
            if (!ConfigManager.isIdentifierEnabled(d.id)) {
                disabled.add(d.displayName);
                continue;
            }
            String v = ConfigManager.getIdentifierValue(d.id);
            if (v == null || v.isEmpty()) {
                badFormat.add(d.displayName + " (empty)");
                continue;
            }
            if (!d.isValid(v)) badFormat.add(d.displayName + " (" + v + ")");
            if (("imei".equals(d.id) || "iccid".equals(d.id)) && v.matches("\\d+")
                    && !luhnValid(v)) {
                badLuhn.add(d.displayName + " (" + v + ")");
            }
        }
        if (!badLuhn.isEmpty()) {
            out.add(new Check(Level.FAIL, "Invalid check digit",
                    "These can never belong to a real device (Luhn): " + join(badLuhn) + "."));
        }
        if (!badFormat.isEmpty()) {
            out.add(new Check(Level.WARN, "Malformed identifiers",
                    "Values outside their real-world format: " + join(badFormat) + "."));
        }
        if (badLuhn.isEmpty() && badFormat.isEmpty()) {
            out.add(new Check(Level.PASS, "Identifier formats",
                    "Every enabled identifier matches its real-world format."));
        }
        if (!disabled.isEmpty()) {
            out.add(new Check(Level.WARN, "Real values visible",
                    "Not spoofed, so scoped apps see the real value: " + join(disabled) + "."));
        }
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (dbl) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    // ---- SIM vs locale/timezone ----

    private static void checkSimCoherence(List<Check> out) {
        if (!ConfigManager.isIdentifierEnabled("imsi")) return;
        String imsi = ConfigManager.getIdentifierValue("imsi");
        if (imsi == null || imsi.length() < 3 || !imsi.matches("\\d+")) return;
        String simCountry = MCC_COUNTRY.get(imsi.substring(0, 3));
        if (simCountry == null) return;
        List<String> bad = new ArrayList<>();
        String localeCountry = ConfigManager.getRawProperty("locale.country");
        if (localeCountry != null && !localeCountry.isEmpty()
                && !simCountry.equalsIgnoreCase(localeCountry)) {
            bad.add("locale=" + localeCountry);
        }
        String tz = ConfigManager.getTimeZoneId();
        String tzCountry = tz == null ? null : TZ_COUNTRY.get(tz);
        if (tzCountry != null && !simCountry.equals(tzCountry)) {
            bad.add("timezone=" + tz + " (" + tzCountry + ")");
        }
        if (bad.isEmpty()) {
            out.add(new Check(Level.PASS, "SIM / locale / timezone",
                    "Spoofed SIM country (" + simCountry + ") matches locale and timezone."));
        } else {
            out.add(new Check(Level.WARN, "SIM country vs locale/timezone",
                    "Spoofed SIM is " + simCountry + " but " + join(bad)
                            + " — possible on dual-SIM/roaming setups, but an app can notice."));
        }
    }

    // ---- display / cpu / memory / UA ----

    private static void checkDisplay(List<Check> out) {
        String model = ConfigManager.getBuildModel();
        if (model != null && !model.isEmpty() && !ConfigManager.isDisplaySpoofEnabled()) {
            out.add(new Check(Level.WARN, "Screen metrics stay real",
                    "Model is spoofed but display spoof is off — real resolution/density can betray the model."));
        }
        int w = ConfigManager.getScreenWidth();
        int h = ConfigManager.getScreenHeight();
        int d = ConfigManager.getScreenDensity();
        if (w <= 0 || h <= 0 || d < 120 || d > 640) {
            out.add(new Check(Level.WARN, "Screen metrics out of range",
                    "Spoofed screen is " + w + "x" + h + " @ " + d + "dpi."));
        } else {
            out.add(new Check(Level.PASS, "Screen metrics",
                    "Spoofed screen " + w + "x" + h + " @ " + d + "dpi is plausible."));
        }
    }

    private static void checkCpu(List<Check> out) {
        String[] parts = splitCsv(ConfigManager.getCpuInfoParts());
        String[] variants = splitCsv(ConfigManager.getCpuInfoVariants());
        String[] revisions = splitCsv(ConfigManager.getCpuInfoRevisions());
        if (parts.length != variants.length || parts.length != revisions.length) {
            out.add(new Check(Level.FAIL, "CPU core tables disagree",
                    "/proc/cpuinfo lists " + parts.length + " parts, " + variants.length
                            + " variants, " + revisions.length + " revisions — a reader sees the mismatch."));
        } else {
            out.add(new Check(Level.PASS, "CPU core tables",
                    "cpuinfo parts/variants/revisions all list " + parts.length + " cores."));
            int cores = ConfigManager.getCpuCoreCount();
            if (cores != parts.length) {
                out.add(new Check(Level.WARN, "CPU core count vs tables",
                        "Core count=" + cores + " but cpuinfo tables list " + parts.length + " cores."));
            }
        }
    }

    private static String[] splitCsv(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split(",");
    }

    private static void checkMemory(List<Check> out) {
        long total = ConfigManager.getMemoryTotalKb();
        long avail = ConfigManager.getMemoryAvailableKb();
        if (total <= 0 || avail <= 0 || avail > total) {
            out.add(new Check(Level.WARN, "Memory values out of range",
                    "Spoofed memory total=" + total + "KB, available=" + avail + "KB."));
        }
    }

    private static void checkUserAgent(List<Check> out) {
        String ua = ConfigManager.getWebViewUserAgent();
        if (ua == null) {
            out.add(new Check(Level.PASS, "WebView UA",
                    "No custom UA — WebView UA is rebuilt from the spoofed model and Android version."));
            return;
        }
        String model = ConfigManager.getBuildModel();
        String release = ConfigManager.getBuildVersionRelease();
        boolean ok = (model == null || model.isEmpty() || ua.contains(model))
                && (release == null || release.isEmpty() || ua.contains(release));
        if (ok) {
            out.add(new Check(Level.PASS, "WebView UA",
                    "Custom WebView UA names the spoofed model and version."));
        } else {
            out.add(new Check(Level.FAIL, "WebView UA betrays the persona",
                    "Custom UA does not name the spoofed model/version: " + ua));
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private ConsistencyAudit() {}
}
