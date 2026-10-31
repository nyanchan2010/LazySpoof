plugins {
    // AGP 9 has built-in Kotlin: it applies KGP 2.2.10 itself, so the
    // kotlin plugins must NOT be applied here (duplicate 'kotlin'
    // extension error). Only the AGP plugin is declared.
    id("com.android.application") version "9.1.1" apply false
}
