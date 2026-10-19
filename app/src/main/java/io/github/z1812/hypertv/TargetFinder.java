package io.github.z1812.hypertv;

import android.app.Activity;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import io.github.libxposed.api.XposedInterface;

/**
 * 用 DexKit 在运行时按「代码结构」定位宿主里的目标类/方法/字段，完全不依赖混淆后的名字。
 *
 * <h3>定位依据（都是结构性特征，不是名字）</h3>
 * <ol>
 *   <li>列表页会有一个「造行」方法：<b>返回值类型是入参类型的子类</b>，且只有一个参数
 *       —— 因为行对象本身继承自条目基类（{@code Block extends DisplayItem}），
 *       造行时直接拿条目当参数。普通 setter/getter 不会长这样。</li>
 *   <li>「造行」方法被一个 <b>无参 void</b> 方法调用 —— 就是「先清空行列表，再 add(造行(...))」的那个装载方法。</li>
 *   <li>所在类必须是 Activity，且声明了 <b>3 个以上</b>同类型的行对象字段（数据/本地/推荐/预推荐）。</li>
 *   <li>该类里还有一个 public {@code void xxx(行对象)} —— 服务端数据回调。</li>
 *   <li>装载方法里被<b>写入</b>的那个 List 类型字段就是行列表（blocks）。</li>
 * </ol>
 *
 * <p>命中后仍会在运行时二次校验（只有「一行里塞了多个同一个对象」的推荐 / 广告行才会被摘掉），
 * 所以就算结构匹配到别的页面也不会误删正常数据。
 */
public final class TargetFinder {

    private static final String TAG = HookEntry.TAG;

    /** 只用来缩小 DexKit 的搜索范围（就是模块 scope，不是混淆标识）。 */
    private static final String PKG = HookEntry.TARGET_PACKAGE;

    /** 单个页面的定位结果。 */
    public static final class Result {
        public final Class<?> activityClass;
        public final Class<?> blockClass;
        /** 往行列表里塞本地推荐行的无参 void 方法。 */
        public final Method loadRows;
        /** 服务端推荐数据回调 {@code (Block) void}。 */
        public final Method serverCallback;
        /** Block 里的行列表字段（List）。 */
        public final Field rowsField;

        Result(Class<?> activityClass, Class<?> blockClass, Method loadRows,
               Method serverCallback, Field rowsField) {
            this.activityClass = activityClass;
            this.blockClass = blockClass;
            this.loadRows = loadRows;
            this.serverCallback = serverCallback;
            this.rowsField = rowsField;
        }
    }

    private TargetFinder() {
    }

    public static List<Result> find(ClassLoader loader, XposedInterface module) {
        List<Result> results = new ArrayList<>();
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "load dexkit native library failed", t);
            return results;
        }
        try (DexKitBridge bridge = DexKitBridge.create(loader, false)) {
            MethodDataList singleArg = bridge.findMethod(new FindMethod()
                    .searchPackages(PKG)
                    .matcher(new MethodMatcher().paramCount(1)));
            module.log(Log.DEBUG, TAG, "single-arg methods: " + singleArg.size());
            for (MethodData method : singleArg) {
                if (!cheapFilter(method)) {
                    continue;
                }
                try {
                    Result result = resolve(method, loader, module);
                    if (result != null && !duplicated(results, result)) {
                        results.add(result);
                    }
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "resolve " + method.getMethodSign() + " failed", t);
                }
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "dexkit discovery failed", t);
        }
        return results;
    }

    /** 纯字符串层面的初筛，避免对几万个单参方法都做类型解析。 */
    private static boolean cheapFilter(MethodData method) {
        if (method.getParamCount() != 1) {
            return false;
        }
        try {
            String returnType = method.getReturnTypeName();
            List<String> params = method.getParamTypeNames();
            if (returnType == null || params == null || params.size() != 1) {
                return false;
            }
            String paramType = params.get(0);
            if (paramType == null || returnType.equals(paramType)) {
                return false;
            }
            return !isPrimitive(paramType) && !isPrimitive(returnType);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPrimitive(String typeName) {
        switch (typeName) {
            case "void":
            case "boolean":
            case "byte":
            case "char":
            case "short":
            case "int":
            case "float":
            case "long":
            case "double":
                return true;
            default:
                return typeName.endsWith("[]");
        }
    }

    private static boolean duplicated(List<Result> results, Result candidate) {
        for (Result r : results) {
            if (r.loadRows.equals(candidate.loadRows)) {
                return true;
            }
        }
        return false;
    }

    private static Result resolve(MethodData rowFactory, ClassLoader loader, XposedInterface module) {
        // 1. 返回值类型必须是入参类型的子类（造行：Block extends DisplayItem）
        ClassDataList params = rowFactory.getParamTypes();
        ClassData returnType = rowFactory.getReturnType();
        if (params.size() != 1 || returnType == null) {
            return null;
        }
        ClassData paramType = params.get(0);
        if (paramType == null || paramType.getName().equals(returnType.getName())) {
            return null;
        }
        if (!isAncestor(paramType, returnType)) {
            return null;
        }

        // 2. 所在类必须是 Activity，且持有多个行对象
        ClassData owner = rowFactory.getDeclaredClass();
        if (owner == null || countFieldsOfType(owner, returnType.getName()) < 3) {
            return null;
        }
        Class<?> activityClass;
        try {
            activityClass = owner.getInstance(loader);
        } catch (Throwable t) {
            return null;
        }
        if (!Activity.class.isAssignableFrom(activityClass)) {
            return null;
        }

        // 3. 调用它的无参 void 方法 = 行装载方法
        MethodData loadRows = null;
        for (MethodData caller : rowFactory.getCallers()) {
            if (caller.getParamCount() == 0
                    && "void".equals(caller.getReturnTypeName())
                    && owner.getName().equals(caller.getDeclaredClassName())) {
                loadRows = caller;
                break;
            }
        }
        if (loadRows == null) {
            loadRows = findLoaderByInvokes(owner, rowFactory);
        }
        if (loadRows == null) {
            return null;
        }

        // 4. public void (行对象) = 服务端数据回调
        MethodData serverCallback = null;
        for (MethodData m : owner.findMethod(new FindMethod()
                .matcher(new MethodMatcher().paramTypes(returnType.getName())))) {
            if ("void".equals(m.getReturnTypeName()) && Modifier.isPublic(m.getModifiers())) {
                serverCallback = m;
                break;
            }
        }
        if (serverCallback == null) {
            return null;
        }

        // 5. 装载方法里被写入的 List 字段 = 行列表
        FieldData rowsField = null;
        for (UsingFieldData using : loadRows.getUsingFields()) {
            FieldData field = using.getField();
            if (field == null || !returnType.getName().equals(field.getDeclaredClassName())) {
                continue;
            }
            String type = field.getTypeName();
            if (using.getUsingType() == FieldUsingType.Write && type != null && isListType(type)) {
                rowsField = field;
                break;
            }
        }
        if (rowsField == null) {
            return null;
        }

        try {
            Class<?> blockClass = returnType.getInstance(loader);
            Method load = loadRows.getMethodInstance(loader);
            Method callback = serverCallback.getMethodInstance(loader);
            Field rows = rowsField.getFieldInstance(loader);
            load.setAccessible(true);
            callback.setAccessible(true);
            rows.setAccessible(true);
            module.log(Log.INFO, TAG, "resolved " + activityClass.getName()
                    + " {loadRows=" + load.getName() + ", serverCallback=" + callback.getName()
                    + ", rows=" + rows.getName() + ", block=" + blockClass.getName() + "}");
            return new Result(activityClass, blockClass, load, callback, rows);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "instantiate " + owner.getName() + " failed", t);
            return null;
        }
    }

    /** 反向兜底：扫无参 void 方法，看谁调用了造行方法。 */
    private static MethodData findLoaderByInvokes(ClassData owner, MethodData rowFactory) {
        String factorySign = rowFactory.getMethodSign();
        MethodDataList noArgVoid = owner.findMethod(new FindMethod()
                .matcher(new MethodMatcher().paramCount(0).returnType("void")));
        for (MethodData method : noArgVoid) {
            for (MethodData callee : method.getInvokes()) {
                if (factorySign.equals(callee.getMethodSign())) {
                    return method;
                }
            }
        }
        return null;
    }

    /** param 是否是 type 的（严格）父类。 */
    private static boolean isAncestor(ClassData param, ClassData type) {
        ClassData current = type.getSuperClass();
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (param.getName().equals(current.getName())) {
                return true;
            }
            current = current.getSuperClass();
        }
        return false;
    }

    private static int countFieldsOfType(ClassData owner, String typeName) {
        int count = 0;
        for (FieldData field : owner.getFields()) {
            if (typeName.equals(field.getTypeName())) {
                count++;
            }
        }
        return count;
    }

    private static boolean isListType(String typeName) {
        return typeName.endsWith("List") || typeName.endsWith("ArrayList") || typeName.endsWith("Collection");
    }
}
