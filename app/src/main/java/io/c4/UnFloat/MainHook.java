package io.c4.UnFloat;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainHook implements IXposedHookLoadPackage {
    private static final String WUWA_PACKAGE = "com.kuro.cloudgame";
    private static final String YH_PACKAGE = "com.pwrd.cloud.yh.laohu";
    private static final String WUWA_BINDING_IMPL =
            "com.kuro.cloudgame.databinding.CloudgameActivityGameBindingImpl";
    private static final String LOTTIE_CLASS_NAME = "com.airbnb.lottie.LottieAnimationView";
    private static final String FLOAT_ICON_ID_NAME = "clIcon";

    private static Unhook yhUnhook;
    private static Unhook floatIconAddViewUnhook;
    private static final AtomicBoolean floatIconDone = new AtomicBoolean(false);

    private static final String[] IMAGE_FIELDS = {
            "imageBg",
            "imageBg2",
            "imageBg3",
            "imageRestartBg",
            "loadingBg",
            "ivLogo"
    };

    private static final String[] LOTTIE_FIELDS = {
            "enterAnimation"
    };

    private static final String[] TEXT_FIELDS = {
            "tvEnter"
    };

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.processName.equals(lpparam.packageName)) return;

        if (YH_PACKAGE.equals(lpparam.packageName)) {
            hookYhFloatMenu();
            return;
        }

        hookVisibilityForFloatViews();
        hookFloatViewAboveByClIcon();

        if (WUWA_PACKAGE.equals(lpparam.packageName)) {
            hookWuwaCloudGameBinding(lpparam);
        }
    }

    private void hookVisibilityForFloatViews() {
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

    private void hookFloatViewAboveByClIcon() {
        if (floatIconAddViewUnhook != null) return;

        floatIconAddViewUnhook = XposedHelpers.findAndHookMethod(
                ViewGroup.class,
                "addView",
                View.class,
                int.class,
                ViewGroup.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (floatIconDone.get()) return;

                        Object childObj = param.args[0];
                        if (!(childObj instanceof View)) return;

                        View child = (View) childObj;
                        View target = findFloatIconTarget(child);
                        if (target == null) return;

                        target.setVisibility(View.VISIBLE);
                        target.setAlpha(0f);

                        if (floatIconDone.compareAndSet(false, true) && floatIconAddViewUnhook != null) {
                            floatIconAddViewUnhook.unhook();
                            floatIconAddViewUnhook = null;
                        }
}
                }
        );
    }

    private void hookWuwaCloudGameBinding(LoadPackageParam lpparam) {
        try {
            Class<?> bindingImplClass = XposedHelpers.findClass(WUWA_BINDING_IMPL, lpparam.classLoader);
            final Set<Unhook>[] unhooksRef = new Set[1];
            unhooksRef[0] = XposedBridge.hookAllConstructors(bindingImplClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object binding = param.thisObject;

                    boolean handledAny = false;

                    for (String fieldName : IMAGE_FIELDS) {
                        handledAny = clearImageField(binding, fieldName) || handledAny;
                    }
                    for (String fieldName : LOTTIE_FIELDS) {
                        handledAny = clearLottieField(binding, fieldName) || handledAny;
                    }
                    for (String fieldName : TEXT_FIELDS) {
                        handledAny = clearTextField(binding, fieldName) || handledAny;
                    }

                    handledAny = hideEnterAnimationById(binding) || handledAny;

                    if (handledAny) {
                        for (Unhook unhook : unhooksRef[0]) {
                            unhook.unhook();
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookYhFloatMenu() {
        yhUnhook = XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl",
                null,
                "addView",
                View.class,
                ViewGroup.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View v = find((View) param.args[0], "ll_float_menu");
                            v.setAlpha(0f);
                            yhUnhook.unhook();
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );
    }

    private boolean clearImageField(Object binding, String fieldName) {
        try {
            Object obj = XposedHelpers.getObjectField(binding, fieldName);
            if (!(obj instanceof ImageView)) return false;
            ImageView imageView = (ImageView) obj;
            imageView.setAlpha(0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean clearLottieField(Object binding, String fieldName) {
        try {
            Object obj = XposedHelpers.getObjectField(binding, fieldName);
            if (obj == null || !LOTTIE_CLASS_NAME.equals(obj.getClass().getName())) return false;
            if (!(obj instanceof View)) return false;
            View lottieView = (View) obj;
            lottieView.setAlpha(0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean clearTextField(Object binding, String fieldName) {
        try {
            Object obj = XposedHelpers.getObjectField(binding, fieldName);
            if (!(obj instanceof TextView)) return false;
            TextView textView = (TextView) obj;
            textView.setAlpha(0f);
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
            int targetId = root.getResources().getIdentifier("enter_animation", "id", WUWA_PACKAGE);
            if (targetId == 0) return false;
            View target = root.findViewById(targetId);                 
if (target == null || !LOTTIE_CLASS_NAME.equals(target.getClass().getName())) return false;
            target.setAlpha(0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static View findFloatIconTarget(View root) {
        if (root == null) return null;

        if (isFloatIconTarget(root)) return root;

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFloatIconTarget(vg.getChildAt(i));
                if (found != null) return found;
            }
        }

        return null;
    }

    private static boolean isFloatIconTarget(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return false;

        try {
            return FLOAT_ICON_ID_NAME.equals(view.getResources().getResourceEntryName(id));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    private View find(View v, String name) {
        try {
            if (name.equals(v.getResources().getResourceEntryName(v.getId()))) {
                return v;
            }
        } catch (Throwable ignored) {
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), name);
                if (r != null) return r;
            }
        }

        return null;
    }

    private boolean isTarget(View v) {
        String name = v.getClass().getName();
        return name.contains("easyfloat");
    }
}             
