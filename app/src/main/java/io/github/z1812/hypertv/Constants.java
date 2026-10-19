package io.github.z1812.hypertv;

/**
 * UI 进程与 Hook 进程共用的常量。
 *
 * <p>放在独立类里，避免 UI 进程因为引用 {@link HookEntry} 而触发 libxposed api 的类加载
 * （api 是 compileOnly，UI 进程运行时并不存在）。
 */
public final class Constants {

    /** 目标宿主：小米电视桌面。 */
    public static final String TARGET_PACKAGE = "com.mitv.tvhome";

    /** LSPosed 远程偏好组名（= 模块 SharedPreferences 文件名）。 */
    public static final String PREFS_NAME = "HyperTVPrefs";

    /** 功能开关：移除「我的应用」页顶部推荐广告行。 */
    public static final String KEY_REMOVE_MY_APP_ADS = "remove_my_app_ads";
    public static final boolean DEFAULT_REMOVE_MY_APP_ADS = true;

    /** 功能开关：长辈模式首页只保留应用列表，移除其下的节目推荐行。 */
    public static final String KEY_REMOVE_ELDER_RECOMMEND = "remove_elder_recommend";
    public static final boolean DEFAULT_REMOVE_ELDER_RECOMMEND = true;

    /** 功能开关：首页「输入源预览」常显（不必选中预览窗也持续出画面）。 */
    public static final String KEY_PREVIEW_ALWAYS_SHOW = "preview_always_show";
    public static final boolean DEFAULT_PREVIEW_ALWAYS_SHOW = true;

    /** 本模块要求的最低框架 API。 */
    public static final int MIN_API_VERSION = 101;

    private Constants() {
    }
}
