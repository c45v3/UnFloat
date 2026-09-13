package io.c4.UnFloat

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object Log {

    /**
     * 日志总开关，默认关闭 —— 模块正常工作时不需要往宿主的 log 里写东西。
     *
     * 排查问题（定位失败、某个 App 没生效、宿主重建后是否再次命中）时改成 true 重新构建，
     * 日志会打在 `XposedBridge.log`，前缀 `UnFloat |`。
     */
    private const val ENABLED = false

    private const val TAG = "UnFloat"

    fun i(msg: String) {
        if (ENABLED) XposedBridge.log("$TAG | $msg")
    }

    fun w(msg: String, t: Throwable? = null) {
        if (ENABLED) XposedBridge.log("$TAG | $msg" + if (t != null) " -> $t" else "")
    }
}

internal val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

/**
 * 一次性钩子组：命中一次就把自己全部摘掉，之后宿主进程里不再有本模块的回调开销。
 *
 * 摘钩动作 post 到主线程延后一拍，避免「正在派发这个回调时改动回调集合」。
 */
internal class OneShot(private val name: String) {

    private val retired = AtomicBoolean(false)
    private val hooks = ArrayList<XC_MethodHook.Unhook>(4)

    val isRetired: Boolean get() = retired.get()

    fun track(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        synchronized(hooks) { hooks.add(unhook) }
    }

    fun track(unhooks: Collection<XC_MethodHook.Unhook>) {
        synchronized(hooks) { hooks.addAll(unhooks) }
    }

    fun retire(reason: String) {
        if (!retired.compareAndSet(false, true)) return
        mainHandler.post {
            val n = synchronized(hooks) {
                val count = hooks.size
                hooks.forEach { runCatching { it.unhook() } }
                hooks.clear()
                count
            }
            Log.i("$name 摘钩（$reason），释放 $n 处")
        }
    }

    /** 兜底钩子不能一直挂着 —— 到点还没命中就自行退场。 */
    fun retireAfter(ms: Long) {
        mainHandler.postDelayed({ retire("超时未命中") }, ms)
    }
}

/**
 * 只把 View 变透明，绝不动 visibility，也不从视图树里摘掉它。
 *
 * 这是本模块统一的"隐藏"手段。动 visibility（INVISIBLE/GONE）或直接不让宿主创建，
 * 都会改变触摸分发和宿主自己的状态机 —— 比如加载遮罩里的 "点击进入" 是要能点的，
 * 设成 INVISIBLE 就把这一下点击一起吃掉了，玩家会卡住。
 *
 * alpha 归零只改绘制、不改命中测试，宿主该收到的点击照样收得到。
 */
internal fun View.fadeOut() {
    alpha = 0f
}

/**
 * 压 alpha，并在该 View 挂在窗口上的整段生命周期里钉住。
 *
 * 终末地 1.5.1 的 `FloatBallManager` 会自己管 alpha：入场动画插到 1、贴边藏到 0.5、
 * 拖动再拉回 1，写 alpha 贯穿悬浮球的一生。只在 0/300/1000ms 补三次会在 2 秒自动贴边时
 * 被写穿。OnPreDraw 里发现非 0 就压回去，View 卸下时摘掉监听。
 */
internal fun View.fadeOutPersistently() {
    fadeOut()
    if (!pinned.add(this)) return

    val pin = ViewTreeObserver.OnPreDrawListener {
        if (alpha != 0f) fadeOut()
        true
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            runCatching { v.viewTreeObserver.addOnPreDrawListener(pin) }
            v.fadeOut()
        }
        override fun onViewDetachedFromWindow(v: View) {
            runCatching { v.viewTreeObserver.removeOnPreDrawListener(pin) }
        }
    })
    if (isAttachedToWindow) {
        runCatching { viewTreeObserver.addOnPreDrawListener(pin) }
    }
}

private val pinned: MutableSet<View> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

/** 深度优先找出资源名命中的那个 View。资源名不会被 R8 混淆，是跨版本最稳的锚点。 */
internal fun View.findByEntryName(names: Set<String>): View? {
    if (entryNameOrNull() in names) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i)?.findByEntryName(names)?.let { return it }
        }
    }
    return null
}

/** 深度优先找出类名包含某片段的 View（用于 easyfloat 这类第三方悬浮窗容器）。 */
internal fun View.findByClassFragment(fragment: String): View? {
    if (javaClass.name.contains(fragment, ignoreCase = true)) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i)?.findByClassFragment(fragment)?.let { return it }
        }
    }
    return null
}

internal fun View.entryNameOrNull(): String? {
    val id = id
    if (id == View.NO_ID || id == 0) return null
    return runCatching { resources.getResourceEntryName(id) }.getOrNull()
}

/** this 是不是 other 的祖先。用来从一批 View 里挑出彼此独立的顶层容器。 */
internal fun View.isAncestorOf(other: View): Boolean {
    var p = other.parent
    while (p != null) {
        if (p === this) return true
        p = (p as? View)?.parent
    }
    return false
}
