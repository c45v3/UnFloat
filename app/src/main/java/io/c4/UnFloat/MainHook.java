package io.c4.UnFloat;

import android.view.View;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
private static boolean isIntercepted = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
       if (isIntercepted) return;
       if (!lpparam.processName.equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                if (isTarget(v)) {
                    v.setAlpha(0f);
                    isIntercepted = true;                     
                }
            }
        });
    }

    private boolean isTarget(View v) {
        String name = v.getClass().getName();
        return name.contains("FloatViewAbove") || name.contains("easyfloat");
    }
}
