package io.c4.UnFloat

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.wrap.DexMethod
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 三层结构，从精准到通用逐层兜底：
 *
 *   1. 精准隐藏 —— DexKit 按特征找出「创建并挂载悬浮窗」的那个方法，让它照常执行，
 *      再从它这一趟挂上去的视图里挑出悬浮球压成透明（只压球，不碰同树里的菜单）。
 *   2. 通杀兜底 —— 第 1 层没抓到东西时（新版本改了实现、悬浮窗是 post 出去的、
 *      或换了个没适配过的云游戏 App），改为盯住 addView 按资源名认人。资源名不会被 R8 混淆。
 *   3. 加载遮罩 —— 云·鸣潮进游戏前盖住画面的那一层，按 DataBinding 字段名逐个压掉。
 *
 * 三层一律只压 alpha，不动 visibility、也不阻止宿主创建：这样触摸分发和宿主自己的
 * 状态机完全保持原样，该能点的照样能点，只是看不见。
 *
 * ## 钩子的去留
 *
 * 按代价分两类，待遇不同：
 *
 *   - **宿主自己的方法**（悬浮窗创建入口、DataBinding 构造器）—— **常驻不摘**。
 *     它们只在宿主真正创建悬浮窗 / 遮罩时才触发，一局游戏里就那么几次，常驻代价可以忽略。
 *     反过来摘掉的话，宿主重建时就没人管了 —— 云·终末地的 `recreateFloatBall()`
 *     （旋屏、重连都会走）就是这么让悬浮球重新冒出来的。
 *   - **framework 的 addView**（`ViewGroup.addView` / `WindowManagerImpl.addView`）—— **严格限时**。
 *     全 App 每加一个视图都会触发，是真正花钱的地方，所以只在创建入口执行的那几毫秒内布防，
 *     方法一返回立刻撤掉。兜底层没有创建入口可依附，则限时 90 秒后自行退场。
 */
class HookEntry : IXposedHookLoadPackage {

    /** 兜底网全局只装一次，别每次创建入口没捞到东西就再叠一层。 */
    private val netInstalled = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只在主进程干活。DexKit 扫描不便宜，而 :push / :remote 这类子进程里没有悬浮窗。
        if (lpparam.processName != lpparam.packageName) return

        runCatching { work(lpparam) }
            .onFailure { Log.w("初始化失败（${lpparam.packageName}）", it) }
    }

    private fun work(lpparam: XC_LoadPackage.LoadPackageParam) {
        val located = Locator.locate(lpparam)

        val armed = located.floatCreators.count { hideFloatCreator(it, lpparam.classLoader) }
        if (armed == 0) {
            Log.i("未能精准定位悬浮窗入口，改用通杀兜底")
            installFloatNet()
        }

        located.maskBinding?.let { clearLoadingMask(it, lpparam.classLoader) }
    }

    // ── 第 1 层：精准隐藏 ─────────────────────────────────────────────────────

    /**
     * 让创建方法照常跑完，只把它这一趟挂进视图树的悬浮球压成透明。
     *
     * 为什么不是直接拦掉这个方法不让它执行：那等于把悬浮窗整个抹掉，宿主的状态机
     * （字段是否为 null、触摸该往哪儿分发）跟着一起变了，会连带影响点击事件。
     * 现在的做法是宿主该建的照建、该收的点击照收，只是画面上看不见。
     *
     * 「这一趟挂进视图树的」怎么认：进方法时临时挂上 addView 漏斗，出方法时立刻撤掉，
     * 这中间经手 addView 的视图就是它造出来的。不依赖任何字段名，换版本也不会失效。
     *
     * 这个钩子常驻。宿主每重建一次悬浮窗就会再走一遍上面的流程，所以重建出来的球照样被压住。
     */
    private fun hideFloatCreator(descriptor: String, classLoader: ClassLoader): Boolean {
        val method = runCatching { DexMethod(descriptor).getMethodInstance(classLoader) }
            .onFailure { Log.w("描述符解析失败：$descriptor", it) }
            .getOrNull() ?: return false

        val captured = Collections.synchronizedList(ArrayList<View>())
        val funnels = ArrayList<XC_MethodHook.Unhook>(2)
        val depth = AtomicInteger(0)

        val catcher = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.args.getOrNull(0) as? View)?.let { captured.add(it) }
            }
        }

        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (depth.getAndIncrement() != 0) return
                    synchronized(funnels) {
                        captured.clear()
                        funnels += hookAddViewFunnels(catcher)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (depth.decrementAndGet() != 0) return
                    // 立刻撤防。addView 对全 App 生效，多挂一秒都是白花的钱。
                    // 这里是在创建入口的回调里摘 addView 的钩子，两者回调集合不同，同步摘是安全的。
                    synchronized(funnels) {
                        funnels.forEach { runCatching { it.unhook() } }
                        funnels.clear()
                    }
                    if (hideFloatIcon(captured) == 0) {
                        // 悬浮窗可能是 post 出去的，没落在同步窗口里 —— 交给按资源名认人的兜底层
                        Log.i("创建期间未捕获到视图")
                        installFloatNet()
                    }
                }
            })
        }.onFailure {
            Log.w("挂载失败：$descriptor", it)
            return false
        }

        Log.i("已挂载悬浮窗隐藏（常驻，宿主重建时会再次生效）：$descriptor")
        return true
    }

    /**
     * 从这一趟挂上去的视图里挑出「悬浮球」压掉。
     *
     * 不能无差别全压：原神的 `FloatViewManager.B()` 一次就 inflate 出上百个 View —— 悬浮球、
     * 一级菜单、钱包、设置面板全在同一棵树里。早先版本把捕获到的 105 个 View 挨个压成透明，
     * 结果点开菜单也是空白的。
     *
     * 所以先按资源名找真正的球（原神 `clIcon` 是那个 60dp 的 ConstraintLayout，
     * 它的兄弟节点才是菜单），只压它一个，菜单原样保留。
     * 认不出来才退而求其次压最外层容器，但无论如何都不去动子孙节点。
     */
    private fun hideFloatIcon(captured: MutableList<View>): Int {
        val roots = synchronized(captured) { outermost(captured.toList()) }
        if (roots.isEmpty()) return 0

        val icons = roots.mapNotNull { it.findByEntryName(FLOAT_ICON_IDS) }
        val targets = icons.ifEmpty {
            Log.i("未认出悬浮球图标，改压最外层容器（共 ${roots.size} 个）")
            roots
        }
        targets.forEach { view ->
            view.fadeOutPersistently()
            val tag = view.entryNameOrNull()?.let { " @id/$it" }.orEmpty()
            Log.i("已隐藏 ${view.javaClass.name}$tag")
        }
        return targets.size
    }

    /** 挑出彼此不互为祖先的 View —— 即这一趟真正挂上去的那几个顶层容器。 */
    private fun outermost(views: List<View>): List<View> {
        val distinct = views.distinctBy { System.identityHashCode(it) }
        return distinct.filter { v -> distinct.none { it !== v && it.isAncestorOf(v) } }
    }

    // ── 第 2 层：通杀兜底 ─────────────────────────────────────────────────────

    private fun installFloatNet() {
        if (!netInstalled.compareAndSet(false, true)) return

        val shot = OneShot("悬浮窗兜底网")
        val net = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (shot.isRetired) return
                val added = param.args.getOrNull(0) as? View ?: return
                val hit = added.findByEntryName(FLOAT_ICON_IDS)
                    ?: added.findByClassFragment(EASY_FLOAT_MARK)
                    ?: return
                hit.fadeOutPersistently()
                shot.retire("捞到悬浮窗 ${hit.javaClass.name}")
            }
        }

        shot.track(hookAddViewFunnels(net))
        shot.retireAfter(NET_TTL_MS)
    }

    /**
     * 所有 `ViewGroup.addView(...)` 重载最终都汇到 `addView(View, int, LayoutParams)`；
     * 走系统窗口的（包括 `invoke-interface ViewManager.addView` 这种）实际落到
     * `WindowManagerImpl.addView(View, LayoutParams)`。盯这两处就够覆盖。
     */
    private fun hookAddViewFunnels(hook: XC_MethodHook): List<XC_MethodHook.Unhook> {
        val unhooks = ArrayList<XC_MethodHook.Unhook>(2)
        runCatching {
            unhooks += XposedHelpers.findAndHookMethod(
                ViewGroup::class.java, "addView",
                View::class.java, Int::class.javaPrimitiveType, ViewGroup.LayoutParams::class.java,
                hook
            )
        }.onFailure { Log.w("ViewGroup.addView 挂载失败", it) }

        runCatching {
            unhooks += XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", null, "addView",
                View::class.java, ViewGroup.LayoutParams::class.java,
                hook
            )
        }.onFailure { Log.w("WindowManagerImpl.addView 挂载失败", it) }

        return unhooks
    }

    // ── 第 3 层：加载遮罩 ─────────────────────────────────────────────────────

    /**
     * 遮罩这层只把 alpha 归零、不动 visibility —— 里头的 "点击进入" 是要能点的，
     * 设成 INVISIBLE 会把这一下点击也一起吃掉，玩家就卡在那儿了。
     *
     * 同样常驻不摘：Activity 一重建就会 new 一个新的 binding，摘了钩遮罩就又回来了。
     * 它只在 binding 构造时触发，代价可以忽略。
     */
    private fun clearLoadingMask(bindingClass: String, classLoader: ClassLoader) {
        val clazz = runCatching { XposedHelpers.findClass(bindingClass, classLoader) }
            .onFailure { Log.w("找不到遮罩类：$bindingClass", it) }
            .getOrNull() ?: return

        runCatching {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cleared = Locator.MASK_FIELDS.count { name ->
                        val v = runCatching { XposedHelpers.getObjectField(param.thisObject, name) }
                            .getOrNull() as? View ?: return@count false
                        v.fadeOutPersistently()
                        true
                    }
                    if (cleared > 0) Log.i("已压掉 $cleared 层加载遮罩")
                }
            })
        }.onFailure {
            Log.w("挂载遮罩清理失败：$bindingClass", it)
            return
        }

        Log.i("已挂载加载遮罩清理（常驻）：$bindingClass")
    }

    private companion object {
        /**
         * 悬浮球本体的资源 id 名。R8 不会混淆资源名，换版本也认得出。
         * 注意这里要的是"球"而不是整个悬浮窗容器 —— 压容器会连同一棵树里的菜单一起弄没。
         *   clIcon            云·原神 / 云·绝区零（view_float_icon 里 60dp 的 ConstraintLayout）
         *   ll_float_menu     云·异环 悬浮菜单
         *   float_setting_btn 云•鸣潮 cloudgame_float_setting_button / 云·终末地 float_ball_view 里的 ImageView
         */
        val FLOAT_ICON_IDS = setOf("clIcon", "ll_float_menu", "float_setting_btn")

        /** EasyFloat 的容器类名片段，用于兜住任何用这个库做悬浮窗的 App。 */
        const val EASY_FLOAT_MARK = "easyfloat"

        /** 兜底网的存活时间：进游戏前后就该命中了，超过这个时间说明这个 App 压根没有悬浮窗。 */
        const val NET_TTL_MS = 90_000L
    }
}
