package io.github.z1812.hypertv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 UI 侧的配置与框架状态。
 *
 * <h3>为什么这样写</h3>
 * <ul>
 *   <li>配置存两份：本地 SharedPreferences 便于 UI 立刻回显；真正的「实时生效」走
 *       {@link XposedService#getRemotePreferences(String)} —— Hook 进程里的
 *       {@code XposedModule#getRemotePreferences} 读的是同一组数据，框架会推送变更。</li>
 *   <li>框架状态全部来自 LSPosed service（{@link XposedServiceHelper} 绑定），
 *       API 版本与作用域列表都是框架真实返回值，不做任何硬编码假设。</li>
 * </ul>
 */
final class PrefsStore implements XposedServiceHelper.OnServiceListener {

    /** 框架状态变化回调（service 绑定 / 断开）。 */
    interface Observer {
        void onServiceStateChanged();
    }

    private static volatile PrefsStore instance;

    static PrefsStore get(Context context) {
        PrefsStore local = instance;
        if (local == null) {
            synchronized (PrefsStore.class) {
                local = instance;
                if (local == null) {
                    local = new PrefsStore(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final SharedPreferences localPrefs;
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    private volatile XposedService service;

    private volatile SharedPreferences remotePrefs;

    private PrefsStore(Context appContext) {
        localPrefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        // 全局只注册一次；已缓存的 binder 会在注册时立刻回调
        XposedServiceHelper.registerListener(this);
    }

    // ── 框架状态 ────────────────────────────────────────────────────────────────

    boolean isServiceConnected() {
        return service != null;
    }

    int apiVersion() {
        XposedService s = service;
        if (s == null) {
            return 0;
        }
        try {
            return s.getApiVersion();
        } catch (Throwable t) {
            return 0;
        }
    }

    String frameworkName() {
        XposedService s = service;
        if (s == null) {
            return "";
        }
        try {
            String name = s.getFrameworkName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    String frameworkVersion() {
        XposedService s = service;
        if (s == null) {
            return "";
        }
        try {
            String version = s.getFrameworkVersion();
            return version == null ? "" : version;
        } catch (Throwable t) {
            return "";
        }
    }

    List<String> scope() {
        XposedService s = service;
        if (s == null) {
            return Collections.emptyList();
        }
        try {
            List<String> scope = s.getScope();
            return scope == null ? Collections.emptyList() : scope;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // ── 配置读写 ────────────────────────────────────────────────────────────────

    boolean getBoolean(String key, boolean defValue) {
        SharedPreferences remote = remotePrefs;
        if (remote != null) {
            try {
                if (remote.contains(key)) {
                    return remote.getBoolean(key, defValue);
                }
            } catch (Throwable ignored) {
                // 落到本地值
            }
        }
        try {
            return localPrefs.getBoolean(key, defValue);
        } catch (Throwable t) {
            return defValue;
        }
    }

    /**
     * 写入开关。
     *
     * @return true = 已同步到 LSPosed（Hook 立即生效）；false = 只写了本地，等 service 绑定后补同步
     */
    boolean setBoolean(String key, boolean value) {
        localPrefs.edit().putBoolean(key, value).apply();
        SharedPreferences remote = remotePrefs;
        if (remote == null) {
            return false;
        }
        try {
            return remote.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 观察者 ──────────────────────────────────────────────────────────────────

    void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    private void notifyObservers() {
        for (Observer observer : observers) {
            try {
                observer.onServiceStateChanged();
            } catch (Throwable ignored) {
                // 单个 UI 回调异常不影响其它观察者
            }
        }
    }

    // ── XposedServiceHelper.OnServiceListener ──────────────────────────────────

    @Override
    public void onServiceBind(XposedService xposedService) {
        service = xposedService;
        try {
            remotePrefs = xposedService.getRemotePreferences(Constants.PREFS_NAME);
        } catch (Throwable t) {
            remotePrefs = null;
        }
        seedRemoteFromLocal();
        notifyObservers();
    }

    @Override
    public void onServiceDied(XposedService xposedService) {
        service = null;
        remotePrefs = null;
        notifyObservers();
    }

    /** 首次绑定（或本地改过配置）时，把本地已有、远端没有的键补上去。 */
    private void seedRemoteFromLocal() {
        SharedPreferences remote = remotePrefs;
        if (remote == null) {
            return;
        }
        try {
            Map<String, ?> all = localPrefs.getAll();
            if (all.isEmpty()) {
                return;
            }
            SharedPreferences.Editor editor = remote.edit();
            boolean dirty = false;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (remote.contains(key)) {
                    continue;
                }
                if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                    dirty = true;
                }
            }
            if (dirty) {
                editor.commit();
            }
        } catch (Throwable ignored) {
            // 同步失败不影响 UI
        }
    }
}
