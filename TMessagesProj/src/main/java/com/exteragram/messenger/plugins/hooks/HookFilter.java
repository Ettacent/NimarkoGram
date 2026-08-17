package com.exteragram.messenger.plugins.hooks;

public class HookFilter extends app.nimarkogram.messenger.plugins.hooks.HookFilter {

    public HookFilter(String filterType) {
        super(filterType);
    }

    public String getFilterType() {
        return this.filterType;
    }

    public Integer getArgIndex() {
        return this.argIndex;
    }

    public void setArgIndex(Integer argIndex) {
        this.argIndex = argIndex;
    }

    public java.util.ArrayList<app.nimarkogram.messenger.plugins.hooks.HookFilter> getOrFilters() {
        return this.orFilters;
    }

    public void setOrFilters(java.util.ArrayList<app.nimarkogram.messenger.plugins.hooks.HookFilter> orFilters) {
        this.orFilters = orFilters;
    }

    public String getMvelExpression() {
        return this.mvelExpression;
    }

    public void setMvelExpression(String mvelExpression) {
        this.mvelExpression = mvelExpression;
    }

    public Class<?> getInstanceOf() {
        return this.instanceOf;
    }

    public void setInstanceOf(Class<?> instanceOf) {
        this.instanceOf = instanceOf;
    }

    public Object getObject() {
        return this.object;
    }

    public void setObject(Object object) {
        this.object = object;
    }
}
