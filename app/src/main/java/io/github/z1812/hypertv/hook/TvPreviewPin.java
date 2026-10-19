package io.github.z1812.hypertv.hook;

import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.z1812.hypertv.Constants;
import io.github.z1812.hypertv.HookEntry;
import io.github.z1812.hypertv.ModuleConfig;

/**
 * 首页「输入源预览」常显（长辈模式尤甚）。
 *
 * <h3>宿主原有的显示闸门</h3>
 * <ol>
 *   <li>{@code TVSurfaceView.mEnable} —— 广场模式会 {@code setEnable(false)}，彻底不显示。</li>
 *   <li>{@code isMenuIsShowing()} —— 侧边菜单展开即隐藏。</li>
 *   <li>{@code canShow()} —— mask 贴在窗口顶部（Y==0）时给 TvView {@code setAlpha(0)}，画面全透明；
 *       只有 Y&gt;0（卡片离开顶部）才 alpha=1。<b>这是「必须上下选中预览窗才出画面」的直接原因。</b></li>
 *   <li>{@code showTVSurfaceView(show)} —— 需 {@code mFrameStable || isSourceConnectedSafety()}，否则 showView(false)。</li>
 *   <li>{@code showView(false)} —— 把 TvView 的 margin 挪到屏幕外；同时 {@code mVisible=false}，
 *       之后 {@code onScrollChanged()} 不再跟随。</li>
 * </ol>
 * 另一路：{@code onScrolled / onPageScrolled / prevViewFocused / onMenuAnimationStart}
 * 全部收敛到 {@code TVSurfaceViewController.hideInputSourceIfNeed()}。
 *
 * <h3>本 hook 的做法</h3>
 * <ul>
 *   <li>{@code hideInputSourceIfNeed()} 直接空实现 —— 滚动、翻页、菜单、焦点都不会再隐藏。</li>
 *   <li>{@code showView(boolean)} 自建「显示」分支：忽略 mEnable / 菜单 / canShow，强制
 *       {@code mVisible=true}、{@code alpha=1}，并把 margin 对齐到 mask 当前位置。</li>
 *   <li>{@code canShow()} 恒定 true 且 alpha 恒 1，跳过内部的 {@code setAlpha(0)}。</li>
 *   <li>{@code setEnable()} / {@code setPageScrollStatus()} 恒定「启用 / 非滚动」，保持 onScrollChanged 跟随。</li>
 *   <li>{@code updateLayout()/onScrollChanged()} 执行后再兜底同步一次坐标（宿主在 Y==0 时有跳过 setLayoutParams 的分支）。</li>
 *   <li>{@code updateMaskView()} 执行后，若信号确实可显示，把 mask 图层置透明（露出画面，而不是被海报 logo 盖住）。</li>
 * </ul>
 *
 * <p>只处理首页那套（{@code TVSurfaceView}）。信号源整页的另一套 {@code TVSignalView} 不在此列。
 */
public final class TvPreviewPin {

    private static final String TAG = HookEntry.TAG;

    /**
     * 开关来自 LSPosed 偏好设置（{@link Constants#KEY_PREVIEW_ALWAYS_SHOW}，默认开）。
     * <p>每个 hook 入口都读一次最新值，UI 里切换后即时生效；关掉时所有 hook 原样放行。
     */
    private static boolean enabled() {
        return ModuleConfig.previewAlwaysShow();
    }

    /** jadx 输出的 p051tv 是重命名别名，两个名字都试。 */
    private static final String[] VIEW_CLASSES = {
            "com.mitv.tvhome.tv.TVSurfaceView",
            "com.mitv.tvhome.p051tv.TVSurfaceView",
    };
    private static final String[] CONTROLLER_CLASSES = {
            "com.mitv.tvhome.tv.TVSurfaceViewController",
            "com.mitv.tvhome.p051tv.TVSurfaceViewController",
    };

    private final XposedInterface module;
    private final ClassLoader loader;

    /** TVSurfaceView 的私有状态。 */
    private Field mEnable;            // Boolean
    private Field mVisible;           // boolean
    private Field mMaskField;         // View
    private Field mPageScrolling;     // boolean
    private Field mAttachedField;     // boolean

    /** controller 持有的 TVSurfaceViewImpl，用来在 updateMaskView 后补一刀。 */
    private Field mTvSurfaceViewField;
    private Method getMaskViewMethod;
    private Method isSourceCanShowMethod;

    public TvPreviewPin(XposedInterface module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    public void install() {
        Class<?> viewClass = resolve(VIEW_CLASSES, "showView");
        Class<?> controllerClass = resolve(CONTROLLER_CLASSES, "hideInputSourceIfNeed");
        if (viewClass == null || controllerClass == null) {
            module.log(Log.WARN, TAG, "preview classes not found, pin skipped");
            return;
        }

        mEnable = findField(viewClass, "mEnable");
        mVisible = findField(viewClass, "mVisible");
        mMaskField = findField(viewClass, "mMaskView");
        mPageScrolling = findField(viewClass, "mPageScrolling");
        mAttachedField = findField(viewClass, "mAttachedToWindow");
        getMaskViewMethod = findMethod(viewClass, "getMaskView");

        mTvSurfaceViewField = findField(controllerClass, "mTVSurfaceView");
        isSourceCanShowMethod = findMethod(controllerClass, "isSourceCanShow");

        installViewHooks(viewClass);
        installControllerHooks(controllerClass);

        module.log(Log.INFO, TAG, "preview pinned on " + viewClass.getName()
                + " + " + controllerClass.getName() + ", enabled=" + enabled());
    }

    // ------------------------------------------------------------------ view

    private void installViewHooks(Class<?> cls) {
        // 1. 恒 true + alpha 恒 1（跳过内部的 setAlpha(0)）
        Method canShow = findMethod(cls, "canShow");
        if (canShow != null) {
            try {
                module.hook(canShow).intercept(chain -> {
                    if (!enabled()) {
                        return chain.proceed();
                    }
                    Object self = chain.getThisObject();
                    if (self instanceof View) {
                        ((View) self).setAlpha(1.0f);
                    }
                    return Boolean.TRUE;
                });
                module.log(Log.DEBUG, TAG, "hooked canShow");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook canShow failed", t);
            }
        }

        // 2. showView(boolean) —— 核心：自建显示分支，无视所有闸门
        Method showView = findMethod(cls, "showView", boolean.class);
        if (showView != null) {
            try {
                module.hook(showView).intercept(chain -> {
                    if (!enabled()) {
                        return chain.proceed();
                    }
                    Object self = chain.getThisObject();
                    return pin(self) ? Boolean.TRUE : chain.proceed();
                });
                module.log(Log.DEBUG, TAG, "hooked showView");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook showView failed", t);
            }
        }

        // 3. setPageScrollStatus(boolean) —— 恒「非滚动」，让 onScrollChanged 继续跟随
        Method scrollStatus = findMethod(cls, "setPageScrollStatus", boolean.class);
        if (scrollStatus != null) {
            try {
                module.hook(scrollStatus).intercept(chain -> {
                    if (!enabled()) {
                        return chain.proceed();
                    }
                    Object self = chain.getThisObject();
                    try {
                        if (mPageScrolling != null) {
                            mPageScrolling.setBoolean(self, false);
                        }
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                    return null;
                });
                module.log(Log.DEBUG, TAG, "hooked setPageScrollStatus");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook setPageScrollStatus failed", t);
            }
        }

        // 4. setEnable(boolean) —— 恒启用
        Method enable = findMethod(cls, "setEnable", boolean.class);
        if (enable != null) {
            try {
                module.hook(enable).intercept(chain -> {
                    if (!enabled()) {
                        return chain.proceed();
                    }
                    Object self = chain.getThisObject();
                    try {
                        if (mEnable != null) {
                            mEnable.set(self, Boolean.TRUE);
                        }
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                    return null;
                });
                module.log(Log.DEBUG, TAG, "hooked setEnable");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook setEnable failed", t);
            }
        }

        // 5. updateLayout()/updateLayout(boolean)：执行完再兜底对齐一次（宿主在 Y==0 时可能不写 layout）
        Method updateLayout = findMethod(cls, "updateLayout");
        if (updateLayout != null) {
            try {
                module.hook(updateLayout).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) {
                        return result;
                    }
                    syncLayout(chain.getThisObject());
                    return result;
                });
                module.log(Log.DEBUG, TAG, "hooked updateLayout");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook updateLayout failed", t);
            }
        }
        Method updateLayoutFlag = findMethod(cls, "updateLayout", boolean.class);
        if (updateLayoutFlag != null) {
            try {
                module.hook(updateLayoutFlag).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) {
                        return result;
                    }
                    syncLayout(chain.getThisObject());
                    return result;
                });
                module.log(Log.DEBUG, TAG, "hooked updateLayout(boolean)");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook updateLayout(boolean) failed", t);
            }
        }

        // 6. onScrollChanged()：宿主自己会跟随，这里补一刀，防止被 mVisible / visibility 判断挡掉
        Method onScrollChanged = findMethod(cls, "onScrollChanged");
        if (onScrollChanged != null) {
            try {
                module.hook(onScrollChanged).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) {
                        return result;
                    }
                    syncLayout(chain.getThisObject());
                    return result;
                });
                module.log(Log.DEBUG, TAG, "hooked onScrollChanged");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook onScrollChanged failed", t);
            }
        }
    }

    // ------------------------------------------------------------ controller

    private void installControllerHooks(Class<?> cls) {
        // 1. 所有隐藏请求的收敛点 —— 直接空实现
        Method hide = findMethod(cls, "hideInputSourceIfNeed");
        if (hide != null) {
            try {
                module.hook(hide).intercept(chain -> enabled() ? null : chain.proceed());
                module.log(Log.DEBUG, TAG, "hooked hideInputSourceIfNeed");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook hideInputSourceIfNeed failed", t);
            }
        }

        // 2. updateMaskView 是 UI 总同步口：原生「不显示」时会把 mask 调成不透明（盖住画面显示海报 / logo）
        Method updateMaskView = findMethod(cls, "updateMaskView", boolean.class, int.class);
        if (updateMaskView != null) {
            try {
                module.hook(updateMaskView).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) {
                        return result;
                    }
                    Object self = chain.getThisObject();
                    if (Boolean.TRUE.equals(canSourceShow(self))) {
                        View mask = maskOfController(self);
                        if (mask != null) {
                            mask.setAlpha(0.0f);
                        }
                    }
                    return result;
                });
                module.log(Log.DEBUG, TAG, "hooked updateMaskView");
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook updateMaskView failed", t);
            }
        }
    }

    // --------------------------------------------------------------- 逻辑

    /**
     * 强制把 TvView 钉到 mask 的位置并显示。
     *
     * @return true 表示已接管（调用方不要再执行原生逻辑）；false 表示条件不足，交还原生处理
     */
    private boolean pin(Object self) {
        if (!(self instanceof View)) {
            return false;
        }
        View view = (View) self;
        View mask = maskOf(view);
        if (mask == null || !attached(view)) {
            return false;
        }
        try {
            if (mEnable != null) {
                mEnable.set(view, Boolean.TRUE);
            }
            if (mVisible != null) {
                mVisible.setBoolean(view, true);
            }
            view.setAlpha(1.0f);
            if (view.getVisibility() != View.VISIBLE) {
                view.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "pin state failed", t);
            return false;
        }
        syncLayout(view, mask);
        return true;
    }

    /** 只对齐坐标 + 恢复 alpha，不改其他状态（onScrollChanged / updateLayout 之后兜底用）。 */
    private void syncLayout(Object self) {
        if (!(self instanceof View)) {
            return;
        }
        View view = (View) self;
        try {
            if (mVisible != null) {
                mVisible.setBoolean(self, true);
            }
            view.setAlpha(1.0f);
        } catch (Throwable ignored) {
            return;
        }
        View mask = maskOf(view);
        if (mask != null) {
            syncLayout(view, mask);
        }
    }

    private void syncLayout(View view, View mask) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return;
        }
        final int[] location = new int[2];
        mask.getLocationInWindow(location);
        final int width = mask.getWidth();
        final int height = mask.getHeight();
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.leftMargin == location[0] && margins.topMargin == location[1]
                && (width <= 0 || margins.width == width)
                && (height <= 0 || margins.height == height)) {
            return;
        }
        margins.leftMargin = location[0];
        margins.topMargin = location[1];
        if (width > 0) {
            margins.width = width;
        }
        if (height > 0) {
            margins.height = height;
        }
        try {
            view.setLayoutParams(margins);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "sync layout failed", t);
        }
    }

    /** view 侧的 mask（读 mTVSurfaceView.mMaskView 字段）。 */
    private View maskOf(Object view) {
        if (mMaskField == null) {
            return null;
        }
        try {
            Object mask = mMaskField.get(view);
            return mask instanceof View ? (View) mask : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** controller 侧的 mask（mTVSurfaceView.getMaskView()）。 */
    private View maskOfController(Object controller) {
        if (mTvSurfaceViewField == null || getMaskViewMethod == null) {
            return null;
        }
        try {
            Object impl = mTvSurfaceViewField.get(controller);
            return impl == null ? null : (View) getMaskViewMethod.invoke(impl);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean attached(View view) {
        if (mAttachedField == null) {
            return true;
        }
        try {
            return mAttachedField.getBoolean(view);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 当前输入源是否真的可显示（连上了且帧稳定）。 */
    private Boolean canSourceShow(Object controller) {
        if (isSourceCanShowMethod == null) {
            return Boolean.FALSE;
        }
        try {
            Object result = isSourceCanShowMethod.invoke(controller);
            return result instanceof Boolean ? (Boolean) result : Boolean.FALSE;
        } catch (Throwable t) {
            return Boolean.FALSE;
        }
    }

    // ------------------------------------------------------------- 反射工具

    private Class<?> resolve(String[] names, String probeMethod) {
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                if (hasMethodNamed(cls, probeMethod)) {
                    return cls;
                }
            } catch (Throwable ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }

    private static boolean hasMethodNamed(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                // 继续往上找
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 继续往上找
            }
        }
        return null;
    }
}
