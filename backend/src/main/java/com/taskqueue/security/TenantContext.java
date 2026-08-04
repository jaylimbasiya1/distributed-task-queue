package com.taskqueue.security;

import com.taskqueue.domain.Tenant;

public class TenantContext {

    private static final ThreadLocal<Tenant> current = new ThreadLocal<>();

    public static void set(Tenant t) {
        current.set(t);
    }

    public static Tenant get() {
        return current.get();
    }

    public static void clear() {
        current.remove();
    }
}
