package ITM.maint.barcodescan.di;

import android.app.Application;
import android.content.Context;


import java.util.concurrent.Executors;

import javax.inject.Singleton;

import ITM.maint.barcodescan.AppExecutor;
import dagger.Module;
import dagger.Provides;


/**
 * Application level module.
 */
@Module
public class AppModule {

    /**
     * Application context.
     */
    @Provides
    @Singleton
    public static Context getApplicationContext(Application application) {
        return application.getApplicationContext();
    }

    /**
     * AppExecutor.
     */
    @Provides
    @Singleton
    public static AppExecutor provideAppExecutor() {
        return new AppExecutor(
                Executors.newSingleThreadExecutor(),
                Executors.newSingleThreadExecutor(),
                new AppExecutor.MainThreadExecutor());
    }

}