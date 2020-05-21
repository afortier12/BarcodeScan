package ITM.maint.barcodescan.di;

import android.app.Application;

import javax.inject.Singleton;

import ITM.maint.barcodescan.BaseApplication;
import ITM.maint.barcodescan.CodeAnalyzer;
import ITM.maint.barcodescan.MainActivity;
import dagger.BindsInstance;
import dagger.Component;
import dagger.android.AndroidInjector;
import dagger.android.support.AndroidSupportInjectionModule;

/**
 * Application level component.
 */
@Singleton
@Component(
        modules = {
                AndroidSupportInjectionModule.class,
                AppModule.class,
                ActivityModule.class
        }
)
public interface AppComponent extends AndroidInjector<BaseApplication> {

    void inject(MainActivity mainActivity);
    void inject(CodeAnalyzer codeAnalyzer);


    @Component.Builder
    interface Builder {

        @BindsInstance
        Builder application(Application application);

        AppComponent build();
    }

}