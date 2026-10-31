plugins {
    // AGP 9 has built-in Kotlin: it applies KGP 2.2.10 itself, so
    // kotlin.android must NOT be declared (duplicate 'kotlin'
    // extension error). But the Compose compiler plugin IS required
    // (AGP does not auto-apply it) and it needs a version source, so
    // it is declared here with apply false and applied version-less
    // in the app module. Same 2.2.10 as AGP's embedded KGP.
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
