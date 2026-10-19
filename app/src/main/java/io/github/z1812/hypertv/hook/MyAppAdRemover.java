package io.github.z1812.hypertv.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.z1812.hypertv.HookEntry;
import io.github.z1812.hypertv.ModuleConfig;
import io.github.z1812.hypertv.TargetFinder;

/**
 * 「我的应用」页推荐 / 广告行清理。
 *
 * <h3>两个切入点</h3>
 * <ul>
 *   <li><b>行装载方法</b>：它就是「清空行列表 -> add(造行(...))」的那个无参方法。
 *       这里在调用前记下各列表内容，调用后把「这次新增、且一行里塞着多个同一个对象」的行摘掉
 *       —— 那一行就是 3 个一模一样的推荐卡片。摘掉之后行列表为空，
 *       {@code init()} 会把本地应用块插到 0 号位，应用列表直接从第一行开始，不会留白。</li>
 *   <li><b>服务端数据回调</b>：原生会把它插到 0 号位，并且顺手 {@code blocks.remove(0)}
 *       （广告行已经没了，这一下删的就是本地应用块）。所以「我的应用」页上整个回调直接跳过。</li>
 * </ul>
 *
 * <p>只有在行装载方法里真的摘到推荐行、确认是「我的应用」页之后，才会跳过服务端回调；
 * 别的页面万一命中了同一个结构，回调照常执行，不影响原有功能。
 */
public final class MyAppAdRemover {

    private static final String TAG = HookEntry.TAG;

    private final XposedInterface module;
    private final List<TargetFinder.Result> targets;

    /** 已确认是「我的应用」页的 Activity 实例。 */
    private final Set<Object> adPages = Collections.newSetFromMap(new WeakHashMap<>());

    public MyAppAdRemover(XposedInterface module, List<TargetFinder.Result> targets) {
        this.module = module;
        this.targets = targets;
    }

    public void install() {
        for (TargetFinder.Result target : targets) {
            try {
                install(target);
                module.log(Log.INFO, TAG, "hooked " + target.activityClass.getName());
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "hook " + target.activityClass.getName() + " failed", t);
            }
        }
    }

    private void install(final TargetFinder.Result target) throws Throwable {
        module.hook(target.loadRows).intercept(chain -> {
            if (!ModuleConfig.removeMyAppAds()) {
                return chain.proceed();
            }
            Object activity = chain.getThisObject();
            Map<List<?>, List<Object>> before = snapshotRows(activity, target);
            Object result = chain.proceed();
            try {
                int dropped = dropAdRows(activity, target, before);
                if (dropped > 0) {
                    adPages.add(activity);
                    module.log(Log.INFO, TAG, "dropped " + dropped + " ad row(s) in "
                            + activity.getClass().getName());
                }
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "drop ad rows failed", t);
            }
            return result;
        });

        module.hook(target.serverCallback).intercept(chain -> {
            if (!ModuleConfig.removeMyAppAds() || !adPages.contains(chain.getThisObject())) {
                return chain.proceed();
            }
            module.log(Log.INFO, TAG, "server recommend data ignored");
            return null;
        });
    }

    /** 记下 Activity 里所有行列表当前的内容（按列表实例区分）。 */
    private Map<List<?>, List<Object>> snapshotRows(Object activity, TargetFinder.Result target) {
        Map<List<?>, List<Object>> snapshot = new IdentityHashMap<>();
        for (Field field : blockFields(activity, target)) {
            List<?> rows = rowsOf(field, activity, target);
            if (rows != null && !snapshot.containsKey(rows)) {
                snapshot.put(rows, new ArrayList<Object>(rows));
            }
        }
        return snapshot;
    }

    /**
     * 摘掉本次新增、且符合广告行特征的列表项。
     * 广告行特征：一行里放了多个「同一个对象实例」的卡片（原生代码就是 new 一个 item 后 add 3 次）。
     */
    private int dropAdRows(Object activity, TargetFinder.Result target,
                           Map<List<?>, List<Object>> before) {
        int dropped = 0;
        Map<List<?>, Boolean> handled = new IdentityHashMap<>();
        for (Field field : blockFields(activity, target)) {
            List<?> rows = rowsOf(field, activity, target);
            if (rows == null || handled.put(rows, Boolean.TRUE) != null) {
                continue;
            }
            List<Object> old = before.get(rows);
            for (int i = rows.size() - 1; i >= 0; i--) {
                Object row = rows.get(i);
                boolean isNew = old == null || !containsIdentity(old, row);
                if (!isNew || !isAdRow(row)) {
                    continue;
                }
                try {
                    rows.remove(i);
                    dropped++;
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "remove ad row failed", t);
                }
            }
        }
        return dropped;
    }

    /** 行里任意一个 List 字段同时装着 ≥2 个同一个实例，就认定是推荐 / 广告行。 */
    private static boolean isAdRow(Object row) {
        if (row == null) {
            return false;
        }
        for (Class<?> type = row.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(row);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> items = (List<?>) value;
                if (items.size() >= 2 && allSameInstance(items)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean allSameInstance(List<?> items) {
        Object first = items.get(0);
        if (first == null) {
            return false;
        }
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i) != first) {
                return false;
            }
        }
        return true;
    }

    /** Activity（含父类）里所有声明成行对象类型的字段。 */
    private static List<Field> blockFields(Object activity, TargetFinder.Result target) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = activity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != target.blockClass) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                    // 拿不到就跳过
                }
            }
        }
        return fields;
    }

    private static List<?> rowsOf(Field blockField, Object activity, TargetFinder.Result target) {
        try {
            Object block = blockField.get(activity);
            if (block == null) {
                return null;
            }
            Object rows = target.rowsField.get(block);
            return rows instanceof List ? (List<?>) rows : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsIdentity(List<Object> rows, Object row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == row) {
                return true;
            }
        }
        return false;
    }
}
