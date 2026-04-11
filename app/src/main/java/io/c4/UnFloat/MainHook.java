package io.c4.UnFloat;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.util.Set;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.kuro.cloudgame";
    private static final String TARGET_BINDING_IMPL =
            "com.kuro.cloudgame.databinding.CloudgameActivityGameBindingImpl";
    private static final String LOTTIE_CLASS_NAME = "com.airbnb.lottie.LottieAnimationView";

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

        hookVisibilityForFloatViews();

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        hookCloudGameBinding(lpparam);
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

    private void hookCloudGameBinding(LoadPackageParam lpparam) {
        try {
            Class<?> bindingImplClass = XposedHelpers.findClass(TARGET_BINDING_IMPL, lpparam.classLoader);
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
            int targetId = root.getResources().getIdentifier("enter_animation", "id", TARGET_PACKAGE);
            if (targetId == 0) return false;
            View target = root.findViewById(targetId);
            if (target == null || !LOTTIE_CLASS_NAME.equals(target.getClass().getName())) return false;
            target.setAlpha(0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isTarget(View v) {
        String name = v.getClass().getName();
        return name.contains("FloatViewAbove") || name.contains("easyfloat");
    }
}
