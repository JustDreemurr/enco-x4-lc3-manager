package io.github.justdreemurr.encore;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class RouteApplication extends Application implements XposedServiceHelper.OnServiceListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private XposedService service;
    private Runnable observer;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    XposedService service() {
        return service;
    }

    void observe(Runnable observer) {
        this.observer = observer;
    }

    @Override
    public void onServiceBind(XposedService bound) {
        main.post(() -> {
            service = bound;
            if (observer != null) {
                observer.run();
            }
        });
    }

    @Override
    public void onServiceDied(XposedService dead) {
        main.post(() -> {
            if (service == dead) {
                service = null;
                if (observer != null) {
                    observer.run();
                }
            }
        });
    }
}
