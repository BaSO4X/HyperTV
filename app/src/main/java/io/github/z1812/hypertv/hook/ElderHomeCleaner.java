package io.github.z1812.hypertv.hook;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import io.github.libxposed.api.XposedInterface;
import io.github.z1812.hypertv.HookEntry;
import io.github.z1812.hypertv.ModuleConfig;

/**
 * 长辈模式首页：只留应用列表，把应用列表<b>下面</b>的节目 / 内容推荐行全部摘掉。
 *
 * <h3>宿主结构（PatchWall 桌面）</h3>
 * 首页的每一行都是一个 {@code Block}，全部挂在 {@code BlockAdapter}（Leanback 的
 * {@code ArrayObjectAdapter}）的 {@code mBlock.blocks} 上：
 * <ul>
 *   <li>{@code loadBlock(Block)} —— 首屏装载。入参是「页面容器」（{@code block_list} /
 *       {@code single_page} / {@code item_sub_tab} / {@code block_kids_history}），
 *       容器里的 {@code blocks} 才是行列表；不是容器时入参就是单个卡片，内部 {@code blocks}
 *       是它的子块，<b>绝不能当行列表裁</b>（上一版就是在这里把画面预览、账户信息删掉的）。</li>
 *   <li>{@code appendBlock(Block)} —— 翻页 / 下拉刷新追加。入参同样是「一批行」。</li>
 *   <li>{@code replacePageBlocks(Block, int)} —— 按索引就地刷新某几行。不用拦：截断后
 *       目标 index 已经越界，原生开头那句 {@code i >= blocks.size() → return false} 自己会拒。</li>
 * </ul>
 *
 * <h3>做法：按位置截断，而不是按类型筛选</h3>
 * 在每一批行里找到<b>最后一个应用行</b>，把它后面的行全部删掉；它前面的行（画面预览、
 * 账户信息等）原样保留。这样只会伤害「应用列表下方的内容」，永远不会动到顶部区域。
 *
 * <p>翻页是分批追加的，后面的批次里往往没有应用行，所以用 {@code sawAppRow} 记住
 * 「这个 adapter 已经出现过应用行」：之后再来的批次全部位于应用行下方，整批丢弃。
 * 每次 {@code loadBlock}（页面重建）会重置这个标记。
 *
 * <h3>行类型判定</h3>
 * 取 {@code Block.ui_type.name()}，比对值优先从宿主 {@code PresenterUtils} 的常量读，
 * 另有一份字面量兜底（常量被 R8 内联 / 改名时仍然有效）。
 *
 * <p>顺带把 {@code Preferences.getAppRecommendCount()} 在长辈模式下压成 0，压掉应用行
 * 内部的推荐位。
 *
 * <p>开关 {@code Constants.KEY_REMOVE_ELDER_RECOMMEND} 在每个回调里实时读取；配置变更时
 * 还会主动对当前已装载的 adapter 再截断一次，切换开关不用重进页面。
 */
public final class ElderHomeCleaner {

    private static final String TAG = HookEntry.TAG;

    /** 长辈模式：{@code UserModeMgr.getUserMode() == 2}。 */
    private static final int USER_MODE_AGED = 2;

    /** 宿主明文类名（这几个业务类在该 APK 里没有混淆）。 */
    private static final String CLS_USER_MODE_UTILS = "com.mitv.tvhome.business.usermode.UserModeUtils";
    private static final String CLS_USER_MODE_MGR = "com.mitv.tvhome.business.usermode.UserModeMgr";
    private static final String CLS_BLOCK_ADAPTER = "com.mitv.tvhome.mitvui.presenter.BlockAdapter";
    private static final String CLS_PRESENTER_UTILS = "com.mitv.tvhome.presenter.PresenterUtils";
    private static final String CLS_PRESENTER_UTIL = "com.mitv.tvhome.presenter.PresenterUtil";
    private static final String CLS_PREFERENCES = "com.mitv.tvhome.util.Preferences";

    /** 供 DexKit 兜底时缩小搜索范围。 */
    private static final String PKG = HookEntry.TARGET_PACKAGE;

    /** 「这一行就是应用列表」的 ui_type 常量名（值运行时从宿主读）。 */
    private static final String[] APP_BLOCK_CONSTANTS = {
            "UI_TYPE_BLOCK_APP_ROW",
            "NEW_UI_TYPE_BLOCK_APP_ROW",
            "UI_TYPE_BLOCK_LOCAL_APP",
            "NEW_UI_TYPE_BLOCK_LOCAL_APP",
            "UI_TYPE_BLOCK_GRID_APP_ICON",
            "NEW_UI_TYPE_BLOCK_GRID_APP_ICON_LOCAL",
    };

    /**
     * 常量读不到时的字面量兜底。
     *
     * <p>刻意<b>不含</b> {@code myapp_recommend} / {@code block_my_app_nec_card}：
     * 那两个是推荐位和顶部卡片，把它们当应用行会让截断点后移，反而漏删。
     */
    private static final String[] APP_ROW_LITERALS = {
            "block_app_row",
            "new_block_app_row",
            "block_grid_app_icon",
            "new_block_grid_app_icon_local",
            "block_local_app",
            "new_block_local_app",
    };

    /** 「行内这一条是一个应用图标」的 ui_type 常量名。 */
    private static final String[] APP_ITEM_CONSTANTS = {
            "UI_TYPE_LOCAL_APP_ITEM",
            "NEW_UI_TYPE_LOCAL_APP_ITEM",
            "UI_TYPE_ITEM_APP_MORE",
            "UI_TYPE_APP_ITEM",
            "UI_TYPE_DISPLAY_APP_ITEM",
    };

    /** 「这一批行是页面容器」的 ui_type 常量名。 */
    private static final String[] CONTAINER_CONSTANTS = {
            "UI_TYPE_BLOCK_LIST",
            "UI_TYPE_BLOCK_KIDS_HISTORY",
            "UI_TYPE_ITEM_SUB_TAB",
    };

    private static final String[] CONTAINER_LITERALS = {
            "block_list",
            "block_kids_history",
            "single_page",
            "item_sub_tab",
    };

    private final XposedInterface module;
    private final ClassLoader loader;

    private Class<?> adapterClass;

    /** 长辈模式判定：优先 isAgedMode()，退化到 UserModeMgr.getUserMode()==2。 */
    private Method agedMethod;
    private Method mgrGetter;
    private Method modeGetter;
    private Object mgrInstance;
    private boolean gateBroken;

    private Field blocksField;
    private Field itemsField;
    private Field uiTypeField;
    private Field titleField;
    private Field mBlockField;
    private Method uiTypeName;
    private Method notifyChanged;

    private Set<String> appBlockNames = new HashSet<>();
    private Set<String> appItemNames = new HashSet<>();
    private Set<String> containerNames = new HashSet<>();

    /** adapter -> 是否已经出现过应用行（翻页后的批次全在应用行下方）。 */
    private final Map<Object, Boolean> sawAppRow =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /** 已装载过的 adapter，配置变更时用来即时重算。 */
    private final Set<Object> activeAdapters =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    /** 累计摘掉的行数，仅用于日志。 */
    private int droppedRows;

    /** 「容器类型不认识」只提示一次，避免刷屏。 */
    private boolean containerMissLogged;

    /** 「宿主还没初始化完」只提示一次，避免刷屏。 */
    private boolean notReadyLogged;

    public ElderHomeCleaner(XposedInterface module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    public void install() {
        // 开关只在回调里判断：关掉时 hook 仍在，重新打开无需重载桌面
        if (!resolveGate() || !resolveAdapter() || !resolveRowStructure()) {
            return;
        }
        resolvePresenterConstants();
        hookLoadBlock();
        hookAppendBlock();
        // replacePageBlocks 不用拦：它是按索引覆盖，截断后目标 index 已经越界，
        // 原生开头那句 `i >= mBlock.blocks.size() → return false` 自己就拒掉了。
        hookRecommendCount();
        ModuleConfig.addChangeListener(this::onConfigChanged);
        module.log(Log.INFO, TAG, "elder home cleaner installed, enabled="
                + ModuleConfig.removeElderRecommend() + ", app rows=" + appBlockNames
                + ", containers=" + containerNames);
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 两套长辈模式读法都解析好，运行时互为备份：
     * {@code UserModeUtils.isAgedMode()} 内部也是走 {@code UserModeMgr.getInstance()}，
     * 进程早期 Context 没就绪时两边都会抛 NPE，所以不能只留一条路。
     */
    private boolean resolveGate() {
        Class<?> utils = load(CLS_USER_MODE_UTILS);
        if (utils != null) {
            try {
                Method method = findMethod(utils, "isAgedMode", 0);
                if (method != null && method.getReturnType() == boolean.class) {
                    method.setAccessible(true);
                    agedMethod = method;
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "resolve isAgedMode failed", t);
            }
        }
        Class<?> mgr = load(CLS_USER_MODE_MGR);
        if (mgr != null) {
            try {
                Method getInstance = findMethod(mgr, "getInstance", 0);
                Method getUserMode = findMethod(mgr, "getUserMode", 0);
                if (getInstance != null && getUserMode != null) {
                    getInstance.setAccessible(true);
                    getUserMode.setAccessible(true);
                    mgrGetter = getInstance;
                    modeGetter = getUserMode;
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "resolve UserModeMgr failed", t);
            }
        }
        if (agedMethod != null || (mgrGetter != null && modeGetter != null)) {
            return true;
        }
        module.log(Log.ERROR, TAG, "user mode not resolvable, elder cleaner skipped");
        return false;
    }

    private boolean resolveAdapter() {
        Class<?> adapter = load(CLS_BLOCK_ADAPTER);
        if (adapter == null) {
            adapter = findAdapterByDexKit();
        }
        if (adapter == null) {
            module.log(Log.ERROR, TAG, "BlockAdapter not found, elder cleaner skipped");
            return false;
        }
        adapterClass = adapter;
        return true;
    }

    /** 用 loadBlock 的参数类型反推「行对象」类，再取它的 blocks / items / ui_type / title。 */
    private boolean resolveRowStructure() {
        try {
            Method loadBlock = findMethod(adapterClass, "loadBlock", 1);
            Method appendBlock = findMethod(adapterClass, "appendBlock", 1);
            if (loadBlock == null || appendBlock == null) {
                module.log(Log.ERROR, TAG, "BlockAdapter row entries not found, skipped");
                return false;
            }
            Class<?> blockClass = loadBlock.getParameterTypes()[0];
            blocksField = findField(blockClass, "blocks");
            if (blocksField == null || !List.class.isAssignableFrom(blocksField.getType())) {
                module.log(Log.ERROR, TAG, "row list field not found, skipped");
                return false;
            }
            blocksField.setAccessible(true);
            itemsField = accessible(findField(blockClass, "items"));
            titleField = accessible(findField(blockClass, "title"));
            uiTypeField = accessible(findField(blockClass, "ui_type"));
            if (uiTypeField != null) {
                uiTypeName = findMethod(uiTypeField.getType(), "name", 0);
                if (uiTypeName != null) {
                    uiTypeName.setAccessible(true);
                }
            }
            // adapter 自身的行列表 + 刷新入口，配置变更时用
            mBlockField = accessible(findField(adapterClass, "mBlock"));
            notifyChanged = findMethod(adapterClass, "notifyChanged", 0);
            if (notifyChanged != null) {
                notifyChanged.setAccessible(true);
            }
            return true;
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "resolve row structure failed", t);
            return false;
        }
    }

    /** 类名变了（换版本换包）时的兜底：按「继承 ArrayObjectAdapter 且同时有这两个行入口」找。 */
    private Class<?> findAdapterByDexKit() {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "load dexkit failed", t);
            return null;
        }
        try (DexKitBridge bridge = DexKitBridge.create(loader, false)) {
            ClassDataList list = bridge.findClass(new FindClass()
                    .searchPackages(PKG)
                    .matcher(new ClassMatcher().superClass("androidx.leanback.widget.ArrayObjectAdapter")));
            for (ClassData data : list) {
                boolean hasLoad = data.findMethod(new FindMethod()
                        .matcher(new MethodMatcher().name("loadBlock").paramCount(1))).size() > 0;
                boolean hasAppend = data.findMethod(new FindMethod()
                        .matcher(new MethodMatcher().name("appendBlock").paramCount(1))).size() > 0;
                if (hasLoad && hasAppend) {
                    Class<?> found = data.getInstance(loader);
                    module.log(Log.INFO, TAG, "BlockAdapter found by dexkit: " + found.getName());
                    return found;
                }
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "dexkit find BlockAdapter failed", t);
        }
        return null;
    }

    private void resolvePresenterConstants() {
        Class<?> utils = load(CLS_PRESENTER_UTILS);
        Class<?> util = load(CLS_PRESENTER_UTIL);
        appBlockNames = readStringConstants(utils, APP_BLOCK_CONSTANTS);
        appBlockNames.addAll(readStringConstants(util, APP_BLOCK_CONSTANTS));
        // 字面量兜底：常量被内联 / 改名时仍然能认出应用行
        Collections.addAll(appBlockNames, APP_ROW_LITERALS);

        appItemNames = readStringConstants(utils, APP_ITEM_CONSTANTS);
        appItemNames.addAll(readStringConstants(util, APP_ITEM_CONSTANTS));

        containerNames = readStringConstants(utils, CONTAINER_CONSTANTS);
        containerNames.addAll(readStringConstants(util, CONTAINER_CONSTANTS));
        Collections.addAll(containerNames, CONTAINER_LITERALS);
    }

    private Set<String> readStringConstants(Class<?> owner, String[] names) {
        Set<String> values = new HashSet<>();
        if (owner == null) {
            return values;
        }
        for (String name : names) {
            try {
                Field field = owner.getField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String && !((String) value).isEmpty()) {
                    values.add((String) value);
                }
            } catch (Throwable ignored) {
                // 该版本没有这个常量，跳过
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ Hook

    /**
     * 首屏装载。只有入参确实是「页面容器」时才裁，否则它的 {@code blocks} 是卡片内部子块。
     */
    private void hookLoadBlock() {
        Method target = findMethod(adapterClass, "loadBlock", 1);
        if (target == null) {
            module.log(Log.WARN, TAG, "BlockAdapter.loadBlock not found, skipped");
            return;
        }
        try {
            target.setAccessible(true);
            module.hook(target).intercept(chain -> {
                Object adapter = chain.getThisObject();
                // 新页面：重置「已见应用行」标记
                sawAppRow.remove(adapter);
                // 先登记，开关由关切到开时能立刻对这一页重算
                activeAdapters.add(adapter);
                if (!ModuleConfig.removeElderRecommend()) {
                    return chain.proceed();
                }
                try {
                    truncate(adapter, chain.getArg(0), true, "loadBlock");
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "loadBlock truncate failed", t);
                }
                Object result = chain.proceed();
                // 兜底：按 adapter 真实行列表再截一次（容器类型判断失手时仍然生效）
                try {
                    trimAdapterTail(adapter, "loadBlock", false);
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "loadBlock tail trim failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook loadBlock failed", t);
        }
    }

    /** 翻页 / 下拉刷新追加：本批没有应用行时，整个批次都在应用行下方，整批丢弃。 */
    private void hookAppendBlock() {
        Method target = findMethod(adapterClass, "appendBlock", 1);
        if (target == null) {
            module.log(Log.WARN, TAG, "BlockAdapter.appendBlock not found, skipped");
            return;
        }
        try {
            target.setAccessible(true);
            module.hook(target).intercept(chain -> {
                Object adapter = chain.getThisObject();
                activeAdapters.add(adapter);
                if (!ModuleConfig.removeElderRecommend()) {
                    return chain.proceed();
                }
                try {
                    truncate(adapter, chain.getArg(0), false, "appendBlock");
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "appendBlock truncate failed", t);
                }
                Object result = chain.proceed();
                try {
                    trimAdapterTail(adapter, "appendBlock", false);
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "appendBlock tail trim failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook appendBlock failed", t);
        }
    }

    /** 长辈模式下把应用行内部的推荐位数量压成 0。 */
    private void hookRecommendCount() {
        Class<?> prefs = load(CLS_PREFERENCES);
        Method getCount = findMethod(prefs, "getAppRecommendCount", 0);
        if (getCount == null) {
            module.log(Log.WARN, TAG, "getAppRecommendCount not found, in-row recommend kept");
            return;
        }
        try {
            getCount.setAccessible(true);
            module.hook(getCount).intercept(chain -> {
                if (ModuleConfig.removeElderRecommend() && isAged()) {
                    return 0;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook getAppRecommendCount failed", t);
        }
    }

    /**
     * 配置变更后主动重算一次：已经渲染出来的推荐行不用等页面重建就能消失。
     * 回调在框架 binder 线程，切主线程再动 adapter。
     */
    private void onConfigChanged() {
        try {
            new Handler(Looper.getMainLooper()).post(this::trimActiveAdapters);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "config refresh post failed", t);
        }
    }

    private void trimActiveAdapters() {
        if (!ModuleConfig.removeElderRecommend()) {
            return;
        }
        // 模式读不到（进程早期 Context 未就绪）时不整体放弃：之前确实裁过的页面继续裁
        boolean aged = isAged();
        List<Object> snapshot;
        synchronized (activeAdapters) {
            snapshot = new ArrayList<>(activeAdapters);
        }
        for (Object adapter : snapshot) {
            if (!aged && !Boolean.TRUE.equals(sawAppRow.get(adapter))) {
                continue;
            }
            try {
                trimAdapterTail(adapter, "config", true);
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "config refresh failed", t);
            }
        }
    }

    /**
     * 对 adapter 的真实行列表（{@code mBlock.blocks}）做截尾：只保留到最后一个应用行为止。
     * 不依赖容器类型判断，是 {@link #truncate} 的兜底路径。
     *
     * @param skipGate true = 调用方已经判过模式（配置变更回调），不再重复问一次
     */
    private int trimAdapterTail(Object adapter, String from, boolean skipGate) {
        if (!skipGate && !isAged()) {
            return 0;
        }
        List<Object> rows = adapterRows(adapter);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int appIdx = lastAppRowIndex(rows);
        if (appIdx < 0 || appIdx >= rows.size() - 1) {
            return 0;
        }
        String before = typesOf(rows);
        int removed = 0;
        while (rows.size() > appIdx + 1) {
            rows.remove(rows.size() - 1);
            removed++;
        }
        sawAppRow.put(adapter, Boolean.TRUE);
        droppedRows += removed;
        notifyAdapter(adapter);
        module.log(Log.INFO, TAG, from + " tail: dropped " + removed + " row(s) after app row "
                + appIdx + ", " + before + " -> " + typesOf(rows)
                + ", total dropped " + droppedRows);
        return removed;
    }

    // ------------------------------------------------------------------ 裁剪

    /**
     * 按「最后一个应用行」截断一批行。
     *
     * @param requireContainer true = 只有入参是页面容器才处理（loadBlock 单块分支不能碰）
     * @return 删掉的行数
     */
    @SuppressWarnings("unchecked")
    private int truncate(Object adapter, Object container, boolean requireContainer, String from) {
        if (container == null || blocksField == null || !isAged()) {
            return 0;
        }
        if (requireContainer) {
            String type = uiTypeNameOf(container);
            if (type == null || !containerNames.contains(type)) {
                // 单块（画面预览 / 账户信息这类卡片），它内部的 blocks 是子块，不是行
                if (!containerMissLogged) {
                    containerMissLogged = true;
                    Object raw = read(blocksField, container);
                    int size = raw instanceof List ? ((List<?>) raw).size() : -1;
                    module.log(Log.WARN, TAG, "loadBlock ignored: ui_type=" + type
                            + ", blocks=" + size + ", containers=" + containerNames);
                }
                return 0;
            }
        }
        Object raw = read(blocksField, container);
        if (!(raw instanceof List)) {
            return 0;
        }
        List<Object> rows = (List<Object>) raw;
        if (rows.isEmpty()) {
            return 0;
        }
        String before = typesOf(rows);
        int appIdx = lastAppRowIndex(rows);
        int removed = 0;
        if (appIdx >= 0) {
            sawAppRow.put(adapter, Boolean.TRUE);
            while (rows.size() > appIdx + 1) {
                rows.remove(rows.size() - 1);
                removed++;
            }
        } else if (Boolean.TRUE.equals(sawAppRow.get(adapter))) {
            // 后续批次里没有应用行 —— 整批都在应用行下方
            removed = rows.size();
            rows.clear();
        }
        if (removed > 0) {
            droppedRows += removed;
            module.log(Log.INFO, TAG, from + ": dropped " + removed + " row(s) after app row"
                    + " (appIdx=" + appIdx + "), " + before + " -> " + typesOf(rows)
                    + ", total dropped " + droppedRows);
        }
        return removed;
    }

    /** 行列表里最后一个应用行的下标，找不到返回 -1。 */
    private int lastAppRowIndex(List<Object> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (isAppRow(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 把 ui_type 拼成 "[a, b]"，方便对着日志核对首页结构。 */
    private String typesOf(List<Object> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(uiTypeNameOf(rows.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 这一行是不是「应用列表」。 */
    private boolean isAppRow(Object row) {
        if (row == null) {
            return false;
        }
        String name = uiTypeNameOf(row);
        if (name != null && appBlockNames.contains(name)) {
            return true;
        }
        Object title = read(titleField, row);
        if (title instanceof String) {
            String text = (String) title;
            if (text.contains("应用") || text.toLowerCase().contains("app")) {
                return true;
            }
        }
        return allItemsAreApps(row);
    }

    /** 行里非空、且每一条都是应用图标 —— 就是应用网格行。 */
    @SuppressWarnings("unchecked")
    private boolean allItemsAreApps(Object row) {
        if (itemsField == null || appItemNames.isEmpty()) {
            return false;
        }
        Object raw = read(itemsField, row);
        if (!(raw instanceof List)) {
            return false;
        }
        List<Object> items = (List<Object>) raw;
        if (items.isEmpty()) {
            return false;
        }
        for (Object item : items) {
            String name = uiTypeNameOf(item);
            if (name == null || !appItemNames.contains(name)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 工具

    /** adapter 实际在用的行列表（{@code mBlock.blocks}）。 */
    @SuppressWarnings("unchecked")
    private List<Object> adapterRows(Object adapter) {
        Object mBlock = read(mBlockField, adapter);
        Object raw = read(blocksField, mBlock);
        return raw instanceof List ? (List<Object>) raw : null;
    }

    private void notifyAdapter(Object adapter) {
        if (notifyChanged == null) {
            return;
        }
        try {
            notifyChanged.invoke(adapter);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "notifyChanged failed", t);
        }
    }

    private String uiTypeNameOf(Object item) {
        if (uiTypeField == null || uiTypeName == null) {
            return null;
        }
        Object ui = read(uiTypeField, item);
        if (ui == null) {
            return null;
        }
        try {
            Object name = uiTypeName.invoke(ui);
            return name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前是不是长辈模式。
     *
     * <p>{@code UserModeMgr.syncUserMode()} 依赖 {@code Context.getContentResolver()}，
     * 进程刚起来时 Context 还没就绪，会抛 NPE。这种情况<b>不能</b>把 {@link #gateBroken}
     * 永久置位（置了就再也不裁了），只是这一轮返回 false，下次调用再试。
     */
    private boolean isAged() {
        if (gateBroken) {
            return false;
        }
        if (agedMethod != null) {
            try {
                return Boolean.TRUE.equals(agedMethod.invoke(null));
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                if (!isNotReady(cause)) {
                    gateBroken = true;
                    module.log(Log.ERROR, TAG, "read isAgedMode failed, cleaner disabled", cause);
                    return false;
                }
                logNotReady(cause);
            }
        }
        if (mgrGetter != null && modeGetter != null) {
            try {
                if (mgrInstance == null) {
                    mgrInstance = mgrGetter.invoke(null);
                }
                Object mode = modeGetter.invoke(mgrInstance);
                return mode instanceof Integer && (Integer) mode == USER_MODE_AGED;
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                if (!isNotReady(cause)) {
                    gateBroken = true;
                    module.log(Log.ERROR, TAG, "read user mode failed, cleaner disabled", cause);
                    return false;
                }
                logNotReady(cause);
            }
        }
        return false;
    }

    /** 剥掉反射包装，拿到真实异常。 */
    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    /** 宿主还没初始化完（Context 为空）之类的暂时性失败。 */
    private static boolean isNotReady(Throwable t) {
        if (t instanceof NullPointerException || t instanceof IllegalStateException) {
            return true;
        }
        String message = t.getMessage();
        return message != null && message.contains("null object reference");
    }

    /** 未就绪只在第一次记一条，避免每次翻页刷屏。 */
    private void logNotReady(Throwable cause) {
        if (!notReadyLogged) {
            notReadyLogged = true;
            module.log(Log.INFO, TAG, "user mode not ready yet, retry later: " + cause);
        }
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按名字找方法，向上找父类；找不到返回 null。 */
    private static Method findMethod(Class<?> owner, String name, int paramCount) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                    return method;
                }
            }
        }
        return null;
    }

    /** 按名字找字段，向上找父类；找不到返回 null。 */
    private static Field findField(Class<?> owner, String name) {
        if (owner == null || name == null) {
            return null;
        }
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Field accessible(Field field) {
        if (field != null) {
            field.setAccessible(true);
        }
        return field;
    }

    private static Object read(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
