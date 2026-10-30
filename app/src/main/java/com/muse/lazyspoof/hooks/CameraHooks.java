package com.muse.lazyspoof.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CameraHooks {

    private static final String TAG = "LazySpoof-Camera";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cm = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.CameraManager", lpparam.classLoader);
        if (cm == null) return;

        try {
            XposedHelpers.findAndHookMethod(cm, "getCameraIdList",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String[] ids = (String[]) param.getResult();
                            if (ids == null) return;
                            int kept = 0;
                            for (String id : ids) {
                                if (id != null && !id.toLowerCase().contains("emulator")) {
                                    ids[kept++] = id;
                                }
                            }
                            if (kept != ids.length) {
                                String[] trimmed = new String[kept];
                                System.arraycopy(ids, 0, trimmed, 0, kept);
                                param.setResult(trimmed);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook getCameraIdList: " + t);
        }
    }
}
