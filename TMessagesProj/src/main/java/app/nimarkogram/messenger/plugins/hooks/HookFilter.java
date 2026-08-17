package app.nimarkogram.messenger.plugins.hooks;

import android.util.Log;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects; 
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import org.mvel2.MVEL;

public class HookFilter {
    private static final ConcurrentHashMap<String, Serializable> mvelExpressionCache = new ConcurrentHashMap<>();

    private static final ThreadLocal<HashMap<String, Object>> VARS_TL =
            ThreadLocal.withInitial(() -> new HashMap<>(4));

    public final String filterType;
    public Integer argIndex = null;
    public ArrayList<HookFilter> orFilters = null;
    public String mvelExpression = null;
    public Class<?> instanceOf = null;
    public Object object = null;

    public HookFilter(String filterType) {
        this.filterType = filterType;
    }

    public boolean execute(XC_MethodHook.MethodHookParam param, boolean isBeforeHook) {
        try {
            switch (this.filterType) {
                case "or":
                    if (this.orFilters != null) {
                        for (HookFilter filter : this.orFilters) {
                            if (filter.execute(param, isBeforeHook)) {
                                return true;
                            }
                        }
                    }
                    return false;

                case "condition":
                    HashMap<String, Object> vars = VARS_TL.get();
                    
                    vars.clear();
                    vars.put("param", param);
                    vars.put("result", isBeforeHook ? null : param.getResult());
                    vars.put("object", this.object);

                    if (this.mvelExpression != null) {
                        
                        if (mvelExpressionCache.size() > 256) {
                            mvelExpressionCache.clear();
                        }
                        Serializable compiled = mvelExpressionCache.computeIfAbsent(this.mvelExpression, MVEL::compileExpression);
                        Object result = MVEL.executeExpression(compiled, param.thisObject, vars, Boolean.class);
                        return Objects.requireNonNullElse((Boolean) result, Boolean.FALSE);
                    }
                    return false;
            }

            if (this.filterType.startsWith("result_")) {
                if (isBeforeHook) return false;

                Object result = param.getResult();

                switch (this.filterType) {
                    case "result_not_null":
                        return result != null;
                    case "result_is_null":
                        return result == null;
                    case "result_is_true":
                        return (result instanceof Boolean) && (Boolean) result;
                    case "result_is_false":
                        return (result instanceof Boolean) && !((Boolean) result);
                    case "result_equal":
                        return Objects.equals(result, this.object);
                    case "result_not_equal":
                        return !Objects.equals(result, this.object);
                    case "result_is_instance_of":
                        return this.instanceOf != null && result != null && this.instanceOf.equals(result.getClass());
                    default:
                        return false;
                }
            }

            if (this.filterType.startsWith("argument_")) {
                if (this.argIndex == null) return false;
                if (this.argIndex > param.args.length - 1) return false;

                Object arg = param.args[this.argIndex];

                switch (this.filterType) {
                    case "argument_is_null":
                        return arg == null;
                    case "argument_not_null":
                        return arg != null;
                    case "argument_is_true":
                        return (arg instanceof Boolean) && (Boolean) arg;
                    case "argument_is_false":
                        return (arg instanceof Boolean) && !((Boolean) arg);
                    case "argument_equal":
                        return Objects.equals(arg, this.object);
                    case "argument_not_equal":
                        return !Objects.equals(arg, this.object);
                    case "argument_is_instance_of":
                        return this.instanceOf != null && arg != null && this.instanceOf.equals(arg.getClass());
                    default:
                        return false;
                }
            }

        } catch (Exception e) {
            Log.e("HookFilter", "Failed to read file.", e);
        }
        return false;
    }
}