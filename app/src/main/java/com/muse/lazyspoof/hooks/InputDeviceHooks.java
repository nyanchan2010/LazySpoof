package com.muse.lazyspoof.hooks;

import android.view.InputDevice;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class InputDeviceHooks {

    private static final String TAG = "LazySpoof-InputDevice";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(InputDevice.class, "getName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.getResult();
                            if (name != null && isEmulator(name)) {
                                param.setResult("Touch");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook InputDevice.getName: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(InputDevice.class, "getDescriptor",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String desc = (String) param.getResult();
                            if (desc != null && isEmulator(desc)) {
                                param.setResult("0");
                            }
                        }
                    });
        } catch (Throwable t) { /* getDescriptor signature stable */ }
    }

    private static boolean isEmulator(String s) {
        String lower = s.toLowerCase();
        return lower.contains("goldfish") || lower.contains("qemu")
                || lower.contains("ranchu") || lower.contains("vbox");
    }
}
