package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

/**
 * Created by Milk on 4/8/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) continue;
                if (arg instanceof String && i == 0) {
                    args[0] = BlackBoxCore.getHostPkg();
                } else if (arg instanceof android.content.AttributionSource
                        || arg.getClass().getName().equals("android.content.AttributionSource")
                        || (BRAttributionSource.getRealClass() != null && arg.getClass().getName().equals(BRAttributionSource.getRealClass().getName()))) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        try {
                            args[i] = new android.content.AttributionSource.Builder(BlackBoxCore.getHostUid())
                                    .setPackageName(BlackBoxCore.getHostPkg())
                                    .build();
                        } catch (Throwable t) {
                            ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                        }
                    } else {
                        ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                    }
                }
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (Throwable e) {
            if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                throw e.getCause();
            }
            throw e;
        }
    }
}
