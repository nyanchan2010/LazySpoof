package com.muse.lazyspoof.hooks;

import android.os.Build;

import com.muse.lazyspoof.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class EuiccHooks {

    private static final String TAG = "LazySpoof-Euicc";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hook(lpparam, Build.VERSION.SDK_INT);
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam, int realDeviceSdk) {
        if (realDeviceSdk < 28) return;

        Class<?> em = XposedHelpers.findClassIfExists(
                "android.telephony.euicc.EuiccManager", lpparam.classLoader);
        if (em == null) return;

        try {
            XposedHelpers.findAndHookMethod(em, "getEid",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getEid();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook EuiccManager.getEid: " + t);
        }
    }
}
