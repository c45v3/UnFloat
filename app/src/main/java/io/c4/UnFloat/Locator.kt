package io.c4.UnFloat

import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File

/**
 * DexKit 定位结果。存的是描述符字符串而不是 Method/Class 对象，
 * 这样可以原样写进缓存，下次启动直接反射解析，完全不用再拉起 DexKit。
 */
internal data class Located(
    /**
     * 创建并挂载悬浮窗的方法，形如 `Lcom/x/Y;->b(Lz/W;Landroid/graphics/Rect;)V`。
     *
     * 是个列表而不是单个 —— 一个 App 里可能同时挂着好几套悬浮窗（比如云·异环就同时打包了
     * 老虎 SDK 和 pwrd 自己的两套），全都要拦。
     */
    val floatCreators: List<String> = emptyList(),
    /** 承载加载遮罩的 DataBinding 实现类，形如 `com.x.YBindingImpl` */
    val maskBinding: String? = null,
) {
    val isEmpty: Boolean get() = floatCreators.isEmpty() && maskBinding == null
}

/**
 * 把定位结果缓存到宿主自己的 cache 目录。
 *
 * DexKit 全量扫描这几个 App（2.3 万～3.4 万个类）要花几百毫秒到一秒多，
 * 每次冷启动都做一遍太浪费。用 APK 的大小+mtime 当版本戳，宿主一升级就自动失效重扫。
 */
internal object LocateCache {

    private const val FORMAT = "unfloat-v2"

    /** 描述符里不会出现的分隔符 */
    private const val SEP = "|"

    private fun file(lpparam: XC_LoadPackage.LoadPackageParam): File? {
        val dataDir = lpparam.appInfo?.dataDir ?: return null
        return File(File(dataDir, "cache"), "unfloat.locate")
    }

    private fun stamp(lpparam: XC_LoadPackage.LoadPackageParam): String {
        val apk = lpparam.appInfo?.sourceDir ?: return "?"
        val f = File(apk)
        return "${f.length()}:${f.lastModified()}"
    }

    fun read(lpparam: XC_LoadPackage.LoadPackageParam): Located? = runCatching {
        val f = file(lpparam) ?: return null
        if (!f.isFile) return null
        val kv = f.readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        if (kv["format"] != FORMAT || kv["stamp"] != stamp(lpparam)) return null
        Located(
            floatCreators = kv["floatCreators"].orEmpty().split(SEP).filter { it.isNotEmpty() },
            maskBinding = kv["maskBinding"]?.ifEmpty { null },
        )
    }.getOrNull()

    fun write(lpparam: XC_LoadPackage.LoadPackageParam, located: Located) {
        runCatching {
            val f = file(lpparam) ?: return
            f.parentFile?.mkdirs()
            f.writeText(
                buildString {
                    append("format=").append(FORMAT).append('\n')
                    append("stamp=").append(stamp(lpparam)).append('\n')
                    append("floatCreators=").append(located.floatCreators.joinToString(SEP)).append('\n')
                    append("maskBinding=").append(located.maskBinding.orEmpty()).append('\n')
                }
            )
        }.onFailure { Log.w("缓存写入失败", it) }
    }
}

/**
 * 用 DexKit 按「特征」而不是「名字」定位目标。
 *
 * 所有查询只依赖三类不会被 R8 改动的东西：字符串常量、framework/第三方库的类名、
 * 以及类之间的结构关系（继承、字段类型、调用关系）。宿主自己的类名方法名怎么混淆都无所谓。
 */
internal object Locator {

    fun locate(lpparam: XC_LoadPackage.LoadPackageParam): Located {
        LocateCache.read(lpparam)?.let {
            Log.i("命中缓存：$it")
            return it
        }

        val began = System.currentTimeMillis()
        val located = runCatching { scan(lpparam) }
            .onFailure { Log.w("DexKit 扫描失败", it) }
            .getOrDefault(Located())

        Log.i("DexKit 扫描完成，耗时 ${System.currentTimeMillis() - began}ms：$located")
        if (!located.isEmpty) LocateCache.write(lpparam, located)
        return located
    }

    private fun scan(lpparam: XC_LoadPackage.LoadPackageParam): Located {
        System.loadLibrary("dexkit")
        return DexKitBridge.create(lpparam.classLoader, true).use { bridge ->
            Located(
                floatCreators = findFloatCreators(bridge),
                maskBinding = findMaskBinding(bridge),
            )
        }
    }

    // ── 悬浮窗创建入口 ────────────────────────────────────────────────────────

    /**
     * 三种套路全跑一遍再取并集，而不是命中一种就收工 —— 一个 App 里完全可能同时挂着好几套
     * 悬浮窗。反正结果会进缓存，多花的这点扫描时间只在宿主升级后付一次。
     */
    private fun findFloatCreators(bridge: DexKitBridge): List<String> {
        val found = LinkedHashSet<String>()
        val strategies = listOf<Pair<String, (DexKitBridge) -> List<String>>>(
            "原神式" to ::miHoYoStyle,
            "EasyFloat 式" to ::easyFloatStyle,
            "AbstractFloatView 式" to ::abstractFloatViewStyle,
        )
        for ((name, strategy) in strategies) {
            runCatching { strategy(bridge) }
                .onSuccess { hits ->
                    hits.forEach { Log.i("[$name] 悬浮窗创建入口 = $it") }
                    found += hits
                }
                .onFailure { Log.w("[$name] 查询失败", it) }
        }
        return found.toList()
    }

    /**
     * 云·原神：`FloatViewManager.B(viewModel, Rect)`。
     *
     * 特征：Kotlin 的 `getSystemService("window") as WindowManager` 会留下一条固定的转型异常串，
     * 再叠加「void / 两参 / 第二参是 Rect / 内部调用 ViewGroup.addView」。
     */
    private fun miHoYoStyle(bridge: DexKitBridge): List<String> =
        bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher().apply {
                    returnType = "void"
                    paramCount = 2
                    usingStrings(listOf(CAST_WINDOW_MANAGER), StringMatchType.Equals)
                    addInvoke(MethodMatcher().apply {
                        declaredClass = "android.view.ViewGroup"
                        name = "addView"
                    })
                }
            )
        ).filter { it.paramTypeNames.getOrNull(1) == "android.graphics.Rect" }
            .map { it.descriptor }

    /**
     * 云·鸣潮 `GameActivity.initSettingEntrance()`、云·终末地 `FloatBallManager.show()`。
     *
     * 两档，命中一档就收工：
     *
     *   1. 认 tag 字面量 `"Setting"`（鸣潮）。鸣潮同时还用 EasyFloat 挂了排队小窗
     *      （tag `"MiniQueue"`），只认 "Setting" 才不会把排队小窗一起弄没。
     *   2. 不认 tag，只要「无参 void + 把 EasyFloat 的 builder 从 setLayout 一路建到 show」
     *      （终末地）。终末地的 tag 是构造参数传进来的（`"EXIT_VIEW"`），方法体里没有字面量可认。
     *
     * 第 2 档故意排在后面：它在鸣潮里会连排队小窗一起命中，而第 1 档已经先把鸣潮认掉了。
     * 「无参」这个条件同时滤掉了 EasyFloat 自带的 `DragUtils.showAdd(int)` /
     * `showClose(int, ..., ...)` —— 它们也会调到 `Builder.show()`，但都带参数。
     */
    private fun easyFloatStyle(bridge: DexKitBridge): List<String> {
        fun builderCall(method: String) = MethodMatcher().apply {
            declaredClass = EASY_FLOAT_BUILDER
            name = method
        }

        val byTag = MethodMatcher().apply {
            returnType = "void"
            paramCount = 0
            usingStrings(listOf(EASY_FLOAT_SETTING_TAG), StringMatchType.Equals)
            addInvoke(builderCall("show"))
        }
        val byBuilderChain = MethodMatcher().apply {
            returnType = "void"
            paramCount = 0
            addInvoke(builderCall("setLayout"))
            addInvoke(builderCall("show"))
        }
        for (matcher in listOf(byTag, byBuilderChain)) {
            val hits = bridge.findMethod(FindMethod.create().matcher(matcher)).map { it.descriptor }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    /**
     * 云·异环：`FloatWindowManager.a(Context)`。
     *
     * 这个方法体里一个字符串都没有，所以改走结构特征，从一条固定日志串顺藤摸瓜：
     *   1. 带 "!!! context not Activity type!!!" 的类 = AbstractFloatView
     *   2. 它的子类 = 真正的悬浮窗视图
     *   3. 同时持有「该视图」和「WindowManager」两个字段的类 = 视图的持有者
     *   4. void + 单 Context 参 + 内部 new 出该持有者的方法 = 悬浮窗创建入口
     *
     * 每一层都要把所有候选走一遍：云·异环里这条日志串有两份拷贝（老虎 SDK 一套、pwrd
     * 自己一套），而且老虎那套有两个子类。只取第一个会挑错分支，整条链就断了。
     */
    private fun abstractFloatViewStyle(bridge: DexKitBridge): List<String> {
        val bases = bridge.findClass(
            FindClass.create().matcher(
                ClassMatcher().apply { usingStrings(listOf(NOT_ACTIVITY_LOG), StringMatchType.Equals) }
            )
        )
        if (bases.isEmpty()) return emptyList()

        val creators = LinkedHashSet<String>()
        for (base in bases) {
            val views = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher().apply { superClass = base.name }
                )
            )
            for (view in views) {
                val holders = bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher().apply {
                            addFieldForType(view.name, StringMatchType.Equals)
                            addFieldForType("android.view.WindowManager", StringMatchType.Equals)
                        }
                    )
                )
                for (holder in holders) {
                    bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher().apply {
                                returnType = "void"
                                paramTypes("android.content.Context")
                                addInvoke(MethodMatcher().apply {
                                    declaredClass = holder.name
                                    name = "<init>"
                                })
                            }
                        )
                    ).forEach { creators += it.descriptor }
                }
            }
        }
        return creators.toList()
    }

    // ── 加载遮罩所在的 DataBinding 类 ─────────────────────────────────────────

    /**
     * 云·鸣潮的加载遮罩。DataBinding 生成类的字段名直接来自布局里的 id，
     * 不会被混淆，拿这一整组字段名当指纹足够唯一。
     *
     * 命中的是抽象基类，实际要 hook 的是它的实现子类（`...BindingImpl`）——
     * 基类构造器跑完时字段才刚赋值，子类构造器结束时视图树才真正就绪。
     */
    private fun findMaskBinding(bridge: DexKitBridge): String? = runCatching {
        val base = bridge.findClass(
            FindClass.create().matcher(
                ClassMatcher().apply { MASK_FIELDS.forEach { addFieldForName(it, StringMatchType.Equals) } }
            )
        ).firstOrNull() ?: return null

        val impl = bridge.findClass(
            FindClass.create().matcher(
                ClassMatcher().apply { superClass = base.name }
            )
        ).firstOrNull()

        (impl ?: base).name.also { Log.i("[遮罩] DataBinding 类 = $it") }
    }.onFailure { Log.w("定位加载遮罩失败", it) }.getOrNull()

    // ── 特征常量 ──────────────────────────────────────────────────────────────

    private const val CAST_WINDOW_MANAGER =
        "null cannot be cast to non-null type android.view.WindowManager"
    private const val EASY_FLOAT_SETTING_TAG = "Setting"
    private const val EASY_FLOAT_BUILDER = "com.lzf.easyfloat.EasyFloat\$Builder"
    private const val NOT_ACTIVITY_LOG = "!!! context not Activity type!!!"

    /** 鸣潮进游戏前盖住画面的那一层：背景图、Logo、入场动画、"点击进入" 文案。 */
    val MASK_FIELDS = listOf(
        "imageBg", "imageBg2", "imageBg3", "imageRestartBg", "loadingBg",
        "ivLogo", "enterAnimation", "tvEnter",
    )
}
