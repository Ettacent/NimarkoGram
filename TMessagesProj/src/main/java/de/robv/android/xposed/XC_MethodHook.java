package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {

    public int priority;

    public XC_MethodHook() {
        this.priority = 50;
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Member method;
        private Object result;
        private Throwable throwable;
        private Object userData;
        public boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
            this.result = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        public Object getUserData() {
            return userData;
        }

        public void setUserData(Object userData) {
            this.userData = userData;
        }
    }

    public class Unhook implements IXUnhook<XC_MethodHook> {
        private final Member hookMethod;
        
        private Object pineUnhook;

        public Unhook(Member hookMethod) {
            this.hookMethod = hookMethod;
        }

        public Unhook() {
            this.hookMethod = null;
        }

        public Member getHookedMethod() {
            return hookMethod;
        }

        public void setPineUnhook(Object u) {
            this.pineUnhook = u;
        }

        @Override
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        @Override
        public void unhook() {
            Object u = this.pineUnhook;
            if (u == null) return;
            try {
                u.getClass().getMethod("unhook").invoke(u);
            } catch (Throwable t) {
                org.telegram.messenger.FileLog.e("nimarko: Pine unhook failed for "
                        + hookMethod, t);
            } finally {
                this.pineUnhook = null;
            }
        }
    }
}
