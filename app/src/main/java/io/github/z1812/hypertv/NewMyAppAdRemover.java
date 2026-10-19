package io.github.z1812.hypertv;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 小米电视桌面「我的应用」({@code NewMyAppActivity}) 顶部广告行清理。
 *
 * <h3>逆向结论（com.mitv.tvhome 4.8.413 / 100413）</h3>
 * <ul>
 *   <li>{@code NewMyAppActivity.init()} 会先 {@code mData.blocks.addAll(mRecommendData.blocks)}，
 *       再把本地应用列表 block 插到 index 0 或 1，然后交给 {@code BlockAdapter}。</li>
 *   <li>那 3 个广告来自 {@code loadPreRecData()} → {@code createBlock()}：循环 3 次塞同一个
 *       appstore 推广 item（ui_type = {@code myapp_recommend}，block id = {@code CD/20392}，
 *       columns=3 / rows=1），所以看上去就是"顶部一整行 3 个广告"。</li>
 *   <li>联网后 {@code onDataLoaded(Block)} 会把服务端推荐块<b>插到列表最前面</b>，并且顺手
 *       {@code this.mData.blocks.remove(0)} —— 所以只清推荐数据而不拦这个方法，会把本地应用块一起删掉，
 *       变成"广告没了、应用也没了"。</li>
 * </ul>
 *
 * <h3>做法</h3>
 * <ol>
 *   <li>在 {@code BlockAdapter.loadBlock(Block)} 真正装载数据<b>之前</b>把广告行从 block 里摘掉：
 *       此时本地应用块还没插进来，列表自然从第一行开始，不存在"留白/占位"。构造函数里的调用无法被内联，最可靠。</li>
 *   <li>拦掉 {@code onDataLoaded(Block)}：服务端推荐行整块丢弃，同时避免它 remove(0) 误删本地应用块。</li>
 *   <li>拦掉 {@code requestMyAppData()}：不再请求推荐接口（省掉加载圈与广告埋点）。</li>
 *   <li>{@code init()} 结束后再兜底扫一遍适配器，万一有漏网广告行，直接从数据源移除并
 *       notifyItemRangeRemoved，让下面的应用列表立刻顶上来。</li>
 * </ol>
 *
 * <p>所有入口都 try/catch，异常时退回原生逻辑，不影响桌面正常使用。
 */
final class NewMyAppAdRemover {

    private static final String TAG = HookEntry.TAG;

    private static final String CLASS_ACTIVITY = "com.mitv.tvhome.NewMyAppActivity";
    private static final String CLASS_BLOCK = "com.mitv.tvhome.model.Block";
    private static final String CLASS_ADAPTER = "com.mitv.tvhome.mitvui.presenter.BlockAdapter";

    /** 原生 {@code loadPreRecData()} 里写死的推荐行 id。 */
    private static final String AD_ROW_ID = "CD/20392";

    /**
     * "我的应用"专属的推广卡片 ui_type（PresenterUtils.UI_TYPE_MYAPP_RECOMMEND）。
     * 全局 Hook 只认这一个，避免误伤首页等其它页面的正常行。
     */
    private static final String AD_ITEM_UI_TYPE = "myapp_recommend";

    /** 页面内兜底时额外认这些通用广告 item；只要有非广告 item 混进来就不整行删。 */
    private static final String[] AD_ITEM_UI_TYPES = {
            AD_ITEM_UI_TYPE,
            "ad_item",
            "inline_ad_item",
            "inline_multi_ad_item",
            "inline_right_ad_item",
            "channel_ad_item",
            "puzzle_ad_item",
            "oplus_ad_item",
    };

    private final XposedInterface module;
    private final ClassLoader classLoader;

    private Method adapterGetAdapter;

    NewMyAppAdRemover(XposedInterface module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() throws Throwable {
        final Class<?> blockClass = Class.forName(CLASS_BLOCK, false, classLoader);
        final Class<?> adapterClass = Class.forName(CLASS_ADAPTER, false, classLoader);
        final Class<?> activityClass = Class.forName(CLASS_ACTIVITY, false, classLoader);

        // ① 适配器装载数据前先剪掉广告行 —— 应用列表直接顶到第一行
        Method loadBlock = adapterClass.getDeclaredMethod("loadBlock", blockClass);
        loadBlock.setAccessible(true);
        module.hook(loadBlock).intercept(chain -> {
            try {
                Object block = chain.getArg(0);
                int removed = removeAdRows(block);
                if (removed > 0) {
                    module.log(Log.INFO, TAG, "removed " + removed + " ad row(s) before BlockAdapter.loadBlock");
                }
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "strip before loadBlock failed", t);
            }
            return chain.proceed();
        });

        // ② 服务端推荐数据整块丢弃（原生会把它插到最前面，并 remove(0) 误删本地应用块）
        Method onDataLoaded = activityClass.getDeclaredMethod("onDataLoaded", blockClass);
        onDataLoaded.setAccessible(true);
        module.hook(onDataLoaded).intercept(chain -> {
            try {
                Object data = chain.getArg(0);
                Object rows = readField(data, "blocks");
                if (rows instanceof List) {
                    ((List<?>) rows).clear();
                }
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "clear recommend data failed", t);
            }
            module.log(Log.INFO, TAG, "server recommend data ignored");
            return null;
        });

        // ③ 不再拉推荐接口：没有加载圈，也没有广告上报
        try {
            Method requestMyAppData = activityClass.getDeclaredMethod("requestMyAppData");
            requestMyAppData.setAccessible(true);
            module.hook(requestMyAppData).intercept(chain -> {
                module.log(Log.INFO, TAG, "requestMyAppData() skipped");
                return null;
            });
        } catch (NoSuchMethodException e) {
            module.log(Log.WARN, TAG, "requestMyAppData() not found, skip");
        }

        // ④ init() 结束后兜底：理论上前面已经清干净，这里是最后一道保险
        Method init = activityClass.getDeclaredMethod("init");
        init.setAccessible(true);
        module.hook(init).intercept(chain -> {
            Object result = chain.proceed();
            try {
                stripAdapter(chain.getThisObject());
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "post-init strip failed", t);
            }
            return result;
        });
    }

    /** 直接修改 block.blocks，返回删掉的行数。 */
    private int removeAdRows(Object block) {
        Object raw = readField(block, "blocks");
        if (!(raw instanceof List)) {
            return 0;
        }
        List<?> rows = (List<?>) raw;
        int removed = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (isAdRow(rows.get(i), false)) {
                rows.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /**
     * init() 之后的兜底：从适配器的数据源摘掉广告行并通知删除。
     * 走 notifyItemRangeRemoved 而不是 setVisibility，列表会重新排布，下面的应用紧跟上来。
     */
    private void stripAdapter(Object activity) throws Throwable {
        Object fragment = readField(activity, "mFragment");
        if (fragment == null) {
            return;
        }
        if (adapterGetAdapter == null) {
            adapterGetAdapter = findMethod(fragment.getClass(), "getAdapter");
        }
        Object adapter = adapterGetAdapter.invoke(fragment);
        if (adapter == null) {
            return;
        }
        Object root = readField(adapter, "mBlock");
        Object raw = readField(root, "blocks");
        if (!(raw instanceof List)) {
            return;
        }
        List<?> rows = (List<?>) raw;
        int first = -1;
        int removed = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (isAdRow(rows.get(i), true)) {
                rows.remove(i);
                first = i;
                removed++;
            }
        }
        if (removed == 0) {
            return;
        }
        // notifyItemRangeRemoved 在 ObjectAdapter 里是 final protected，只能反射拿
        Method notifyRemoved = findMethod(adapter.getClass(), "notifyItemRangeRemoved", int.class, int.class);
        notifyRemoved.invoke(adapter, first, removed);
        module.log(Log.WARN, TAG, "post-init removed " + removed + " ad row(s) from index " + first);
    }

    /** 沿继承链找方法（含 protected），找到后 setAccessible。 */
    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续往上找
            }
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * 整行都是广告才算广告行：原生推荐行就是 3 个 ui_type 相同的 item。
     *
     * @param genericAds true 时额外认通用广告 item（只在「我的应用」页面内使用）
     */
    private boolean isAdRow(Object row, boolean genericAds) {
        if (row == null) {
            return false;
        }
        if (AD_ROW_ID.equals(readField(row, "f4742id")) || AD_ROW_ID.equals(readField(row, "id"))) {
            return true;
        }
        Object raw = readField(row, "items");
        if (!(raw instanceof List)) {
            return false;
        }
        List<?> items = (List<?>) raw;
        if (items.isEmpty()) {
            return false;
        }
        for (Object item : items) {
            if (!isAdItem(item, genericAds)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAdItem(Object item, boolean genericAds) {
        String name = uiTypeName(readField(item, "ui_type"));
        if (name == null) {
            return false;
        }
        if (AD_ITEM_UI_TYPE.equals(name)) {
            return true;
        }
        if (!genericAds) {
            return false;
        }
        for (String ad : AD_ITEM_UI_TYPES) {
            if (ad.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** DisplayItem.C8131UI#name()。 */
    private static String uiTypeName(Object uiType) {
        if (uiType == null) {
            return null;
        }
        try {
            Object value = uiType.getClass().getMethod("name").invoke(uiType);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射读字段，字段不存在或不可读时返回 null，绝不抛给宿主。 */
    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往上找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
