package ITM.maint.barcodescan;

import dagger.android.AndroidInjector;
import dagger.android.support.DaggerApplication;
import ITM.maint.barcodescan.di.DaggerAppComponent;

public class BaseApplication extends DaggerApplication  {

    @Override
    public void onCreate() {
        super.onCreate();
    }


    @Override
    protected AndroidInjector<? extends DaggerApplication> applicationInjector() {
        return DaggerAppComponent.builder().application(this).build();
    }
}