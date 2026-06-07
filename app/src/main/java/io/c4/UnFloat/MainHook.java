package io.c4.UnFloat;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String WUWA_PACKAGE      = "com.kuro.cloudgame";
    private static final String YH_PACKAGE        = "com.pwrd.cloud.yh.laohu";
    private static final String WUWA_BINDING_IMPL =
            "com.kuro.cloudgame.databinding.CloudgameActivityGameBindingImpl";
    private static final String LOTTIE_CLASS_NAME = "com.airbnb.lottie.LottieAnimationView";
    private static final String FLOAT_ICON_ID     = "clIcon";

    private static final String[] WUWA_CLEAR_FIELDS = {
            "imageBg", "imageBg2", "imageBg3", "imageRestartBg", "loadingBg",
            "ivLogo", "enterAnimation", "tvEnter"
    };

    private Unhook yhUnhook;
    private Unhook floatIconUnhook;
    private final AtomicBoolean floatIconDone = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.processName.equals(lpparam.packageName)) return;

        if (YH_PACKAGE.equals(lpparam.packageName)) {
            hookYhFloatMenu();
            return;
        }

        hookEasyFloat();
        hookFloatIconByAddView();

        if (WUWA_PACKAGE.equals(lpparam.packageName)) {
            hookWuwaBinding(lpparam);
        }
    }

    // ── EasyFloat 通用规则 ────────────────────────────────────────────────────

    private void hookEasyFloat() {
        final Unhook[] ref = new Unhook[1];
        ref[0] = XposedHelpers.findAndHookMethod(
                View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (v.getClass().getName().contains("easyfloat")) {
                            v.setAlpha(0f);
                            ref[0].unhook();
                        }
                    }
                });
    }

    // ── FloatViewAbove / clIcon 规则 ─────────────────────────────────────────

    private void hookFloatIconByAddView() {
        if (floatIconUnhook != null) return;

        floatIconUnhook = XposedHelpers.findAndHookMethod(
                ViewGroup.class, "addView",
                View.class, int.class, ViewGroup.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (floatIconDone.get()) return;

                        View target = findView((View) param.args[0],
                                v -> hasResourceName(v, FLOAT_ICON_ID));
                        if (target == null) return;

                        target.setVisibility(View.VISIBLE);
                        target.setAlpha(0f);

                        if (floatIconDone.compareAndSet(false, true)) {
                            floatIconUnhook.unhook();
                            floatIconUnhook = null;
                        }
                    }
                });
    }

    // ── YH 专用规则 ───────────────────────────────────────────────────────────

    private void hookYhFloatMenu() {
        yhUnhook = XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", null,
                "addView", View.class, ViewGroup.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View v = findView((View) param.args[0],
                                    view -> hasResourceName(view, "ll_float_menu"));
                            if (v == null) return;
                            v.setAlpha(0f);
                            yhUnhook.unhook();
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ── 鸣潮 Loading Mask 规则 ────────────────────────────────────────────────

    private void hookWuwaBinding(LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(WUWA_BINDING_IMPL, lpparam.classLoader);
            final Set<Unhook>[] ref = new Set[1];
            ref[0] = XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object binding = param.thisObject;
                    boolean any = false;

                    for (String field : WUWA_CLEAR_FIELDS) {
                        any = clearViewField(binding, field) || any;
                    }
                    any = hideEnterAnimationById(binding) || any;

                    if (any) {
                        for (Unhook u : ref[0]) u.unhook();
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean clearViewField(Object binding, String fieldName) {
        try {
            Object obj = XposedHelpers.getObjectField(binding, fieldName);
            if (!(obj instanceof View)) return false;
            ((View) obj).setAlpha(0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hideEnterAnimationById(Object binding) {
        try {
            Object rootObj = XposedHelpers.callMethod(binding, "getRoot");
            if (!(rootObj instanceof View)) return false;
            View root = (View) rootObj;
            int id = root.getResources().getIdentifier("enter_animation", "id", WUWA_PACKAGE);
            if (id == 0) return false;
            View target = root.findViewById(id);
            if (target == null || !LOTTIE_CLASS_NAME.equals(target.getClass().getName())) return false;
            target.setAlpha(0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private static View findView(View root, Predicate<View> test) {
        if (root == null) return null;
        if (test.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findView(vg.getChildAt(i), test);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasResourceName(View v, String name) {
        int id = v.getId();
        if (id == View.NO_ID || id == 0) return false;
        try {
            return name.equals(v.getResources().getResourceEntryName(id));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }
}
