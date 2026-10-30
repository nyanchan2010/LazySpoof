package com.muse.lazyspoof.hooks;

import android.os.BatteryManager;

import com.muse.lazyspoof.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// Live charge level is passthrough; only the design counters are overridden.
public class BatteryHooks {

    private static final String TAG = "LazySpoof-Battery";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(BatteryManager.class, "getIntProperty",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int id = (int) param.args[0];
                            if (id == BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) {
                                long capUah = ConfigManager.getBatteryChargeCounterUah();
                                param.setResult((int) Math.min(Integer.MAX_VALUE, capUah));
                            }
                        }
                    });
        } catch (Throwable t) { logFail("getIntProperty", t); }

        try {
            XposedHelpers.findAndHookMethod(BatteryManager.class, "getLongProperty",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int id = (int) param.args[0];
                            if (id == BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) {
                                param.setResult(ConfigManager.getBatteryChargeCounterUah());
                            } else if (id == BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) {
                                param.setResult(ConfigManager.getBatteryEnergyCounterNwh());
                            }
                        }
                    });
        } catch (Throwable t) { logFail("getLongProperty", t); }
    }

    private static void logFail(String what, Throwable t) {
        XposedBridge.log(TAG + ": failed to hook BatteryManager." + what + ": " + t);
    }
}
