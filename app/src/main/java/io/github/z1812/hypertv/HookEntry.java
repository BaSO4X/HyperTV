package io.github.z1812.hypertv;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * 模块入口（libxposed API 101）。
 *
 * <p>只做包名过滤，真正的逻辑在 {@link NewMyAppAdRemover}。
 * <p>作用域：{@code com.mitv.tvhome}（小米电视桌面）。
 */
public final class HookEntry extends XposedModule {

    static final String TAG = "HyperTV";

    /** 电视桌面包名。 */
    private static final String TARGET_PACKAGE = "com.mitv.tvhome";

    /** 每个进程只装一次，避免重复 Hook。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            new NewMyAppAdRemover(this, param.getDefaultClassLoader()).install();
            log(Log.INFO, TAG, "my-app ad remover installed");
        } catch (Throwable t) {
            // 装不上就保持宿主原样，绝不把桌面搞挂
            log(Log.ERROR, TAG, "install failed", t);
        }
    }
}
