package io.github.z1812.hypertv;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook 侧配置读取（LSPosed RemotePreferences）。
 *
 * <p>与模块 UI 共用同一组名 / 键名，UI 写入后框架会把变更推送到目标进程，
 * 因此每个 hook 入口都直接问一次当前值，开关即改即生效。
 *
 * <h3>监听器为什么必须存成静态字段</h3>
 * {@code SharedPreferences.registerOnSharedPreferenceChangeListener} 内部用 WeakHashMap
 * 持有监听器，传一个临时 lambda 而不保留强引用，GC 一轮之后就再也收不到变更 ——
 * 表现就是「UI 里切了开关，桌面侧毫无反应」。
 */
public final class ModuleConfig {

    private static final String TAG = HookEntry.TAG;

    private static volatile SharedPreferences prefs;
    private static volatile boolean initialized;
    private static volatile XposedInterface moduleRef;

    /** 配置变化回调（框架 binder 线程触发，注册方自己切主线程）。 */
    private static final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /** 强引用，不能写成内联 lambda，否则会被 GC 掉导致配置不再推送。 */
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFS_LISTENER =
            (sp, key) -> {
                XposedInterface module = moduleRef;
                if (module != null) {
                    module.log(Log.INFO, TAG, "config changed: " + key);
                }
                notifyChange();
            };

    private ModuleConfig() {
    }

    /** 幂等初始化；失败就退回默认值，绝不因为配置不可用把宿主搞挂。 */
    public static synchronized void init(XposedInterface module) {
        if (initialized) {
            return;
        }
        initialized = true;
        moduleRef = module;
        try {
            SharedPreferences remote = module.getRemotePreferences(Constants.PREFS_NAME);
            remote.registerOnSharedPreferenceChangeListener(PREFS_LISTENER);
            prefs = remote;
            module.log(Log.INFO, TAG, "config ready: removeMyAppAds=" + removeMyAppAds()
                    + ", removeElderRecommend=" + removeElderRecommend()
                    + ", previewAlwaysShow=" + previewAlwaysShow());
        } catch (UnsupportedOperationException e) {
            module.log(Log.WARN, TAG, "remote preferences unsupported, use defaults");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote preferences unavailable, use defaults", t);
        }
    }

    /** 注册配置变化回调（每个 hook 只注册一次），用于开关切换后主动重算一次。 */
    public static void addChangeListener(Runnable listener) {
        if (listener != null && !changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public static boolean removeMyAppAds() {
        return getBoolean(Constants.KEY_REMOVE_MY_APP_ADS, Constants.DEFAULT_REMOVE_MY_APP_ADS);
    }

    /** 长辈模式首页「只留应用列表」。每次 hook 回调都问一次，开关即改即生效。 */
    public static boolean removeElderRecommend() {
        return getBoolean(Constants.KEY_REMOVE_ELDER_RECOMMEND,
                Constants.DEFAULT_REMOVE_ELDER_RECOMMEND);
    }

    /** 首页输入源预览常显。每次 hook 回调都问一次，开关即改即生效。 */
    public static boolean previewAlwaysShow() {
        return getBoolean(Constants.KEY_PREVIEW_ALWAYS_SHOW,
                Constants.DEFAULT_PREVIEW_ALWAYS_SHOW);
    }

    private static boolean getBoolean(String key, boolean defValue) {
        SharedPreferences p = prefs;
        if (p == null) {
            return defValue;
        }
        try {
            return p.getBoolean(key, defValue);
        } catch (Throwable t) {
            return defValue;
        }
    }

    private static void notifyChange() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                XposedInterface module = moduleRef;
                if (module != null) {
                    module.log(Log.WARN, TAG, "config listener failed", t);
                }
            }
        }
    }
}
