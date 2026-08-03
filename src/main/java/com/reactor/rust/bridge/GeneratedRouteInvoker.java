package com.reactor.rust.bridge;

/** Build-time generated direct Java route invocation contract. */
public interface GeneratedRouteInvoker {

    int arity();

    default Object invoke0(Object bean) throws Throwable { return unsupported(); }
    default Object invoke1(Object bean, Object arg0) throws Throwable { return unsupported(); }
    default Object invoke2(Object bean, Object arg0, Object arg1) throws Throwable { return unsupported(); }
    default Object invoke3(Object bean, Object arg0, Object arg1, Object arg2) throws Throwable { return unsupported(); }
    default Object invoke4(Object bean, Object arg0, Object arg1, Object arg2, Object arg3) throws Throwable {
        return unsupported();
    }
    default Object invoke5(Object bean, Object arg0, Object arg1, Object arg2, Object arg3, Object arg4)
            throws Throwable { return unsupported(); }
    default Object invoke6(
            Object bean, Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5)
            throws Throwable { return unsupported(); }
    default Object invoke7(
            Object bean, Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6)
            throws Throwable { return unsupported(); }
    default Object invoke8(
            Object bean, Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7) throws Throwable { return unsupported(); }

    private static Object unsupported() {
        throw new IllegalStateException("Generated route invoker arity mismatch");
    }
}
