# LazySpoof 2.0 (Miuix rebuild)

Fork of `yubunus/DeviceSpoofLab-Hooks` (upstream v1.3), rebuilt from
scratch with a Miuix 0.4.2 (Compose) UI. The hook engine — Java hooks
plus the native LSPlt library — is unchanged from v1.3 plus the
lazy1–lazy7 fixes:

- Scope listing reads the LSPosed daemon first (no root needed), then
  the daemon DB at `/data/adb/lspd/config/modules_config.db`, then the
  root CLI, then hook-seen records.
- QuickReset per app: randomize all identifiers, persist synchronously,
  root `pm clear`, 800ms settle, then launch on the main thread with
  `FLAG_INCLUDE_STOPPED_PACKAGES` (plus MAIN-query and root
  `am start` fallbacks).
- The manifest declares `QUERY_ALL_PACKAGES`, without which Android
  11+ package visibility hides scoped apps from the UI entirely.

## Build (GitHub does it — no local build needed)

Push to `main` and GitHub Actions builds the release APK
(debug-signed, installs over v1.4-lazy7). Download it from the
Actions run's artifacts: **LazySpoof-v2.0-miuix1**.

Enable in LSPosed, reboot, scope your apps, then use Reset & launch
from the Scoped apps card.
