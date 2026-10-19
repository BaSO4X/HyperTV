package io.github.z1812.hypertv;

import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.z1812.hypertv.hook.ElderHomeCleaner;
import io.github.z1812.hypertv.hook.MyAppAdRemover;
import io.github.z1812.hypertv.hook.TvPreviewPin;

/**
 * 模块入口（libxposed API 101）。
 *
 * <p>只做包名过滤 + 位置发现，真正的逻辑在 {@link MyAppAdRemover} 与 {@link ElderHomeCleaner}。
 * <p>作用域：{@code com.mitv.tvhome}（小米电视桌面）。
 */
public final class HookEntry extends XposedModule {

    public static final String TAG = "HyperTV";

    /** 电视桌面包名（模块 scope，同时用作 DexKit 搜索范围）。 */
    public static final String TARGET_PACKAGE = Constants.TARGET_PACKAGE;

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
        long start = SystemClock.elapsedRealtime();

        // 配置（LSPosed RemotePreferences）：hook 回调里每次读最新值，开关即改即生效
        ModuleConfig.init(this);

        // 首页输入源预览常显：与广告清理互不依赖，单独装
        try {
            new TvPreviewPin(this, param.getDefaultClassLoader()).install();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install preview pin failed", t);
        }

        // 长辈模式首页只留应用列表：与上面两个功能互不依赖，单独装
        try {
            new ElderHomeCleaner(this, param.getDefaultClassLoader()).install();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install elder home cleaner failed", t);
        }

        try {
            List<TargetFinder.Result> targets = TargetFinder.find(param.getDefaultClassLoader(), this);
            if (targets.isEmpty()) {
                log(Log.WARN, TAG, "ad-row targets not found, ad remover idle");
                return;
            }
            new MyAppAdRemover(this, targets).install();
            log(Log.INFO, TAG, "installed " + targets.size() + " target(s) in "
                    + (SystemClock.elapsedRealtime() - start) + "ms");
        } catch (Throwable t) {
            // 装不上就保持宿主原样，绝不把桌面搞挂
            log(Log.ERROR, TAG, "install failed", t);
        }
    }
}
