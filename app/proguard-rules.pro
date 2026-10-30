# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Xposed entry point
-keep class com.muse.lazyspoof.MainHook { *; }

# Keep all hook classes
-keep class com.muse.lazyspoof.hooks.** { *; }

# Keep utility classes
-keep class com.muse.lazyspoof.utils.** { *; }
