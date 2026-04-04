package io.c4.UnFloat;

import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
       if (!lpparam.processName.equals(lpparam.packageName)) return;

        final Unhook[] unhookRef = new Unhook[1];
        unhookRef[0] = XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                if (isTarget(v)) {
                    v.setAlpha(0f);
                    unhookRef[0].unhook();
                }
            }
        });
    }

    private boolean isTarget(View v) {
        String name = v.getClass().getName();
        return name.contains("FloatViewAbove") || name.contains("easyfloat");
    }
}
