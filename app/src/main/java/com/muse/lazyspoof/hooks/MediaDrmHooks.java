package com.muse.lazyspoof.hooks;

import com.muse.lazyspoof.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MediaDrmHooks {

    private static final String DEVICE_UNIQUE_ID = "deviceUniqueId";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> mediaDrmClass = XposedHelpers.findClassIfExists(
                "android.media.MediaDrm",
                lpparam.classLoader
        );

        if (mediaDrmClass == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(mediaDrmClass, "getPropertyByteArray",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String propertyName = (String) param.args[0];

                            if (DEVICE_UNIQUE_ID.equals(propertyName)) {
                                byte[] v = ConfigManager.getMediaDrmId();
                                if (v != null) param.setResult(v);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
    }
}
