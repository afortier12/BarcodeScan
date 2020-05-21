package ITM.maint.barcodescan.di;

import ITM.maint.barcodescan.CodeAnalyzer;
import ITM.maint.barcodescan.TestActivity;
import dagger.Module;
import dagger.android.ContributesAndroidInjector;


/**
 * Module for injection dependencies into Android Framework clients.
 */
@Module
public abstract class ActivityModule {

    @ContributesAndroidInjector
    public abstract TestActivity testActivity();

    @ContributesAndroidInjector
    public abstract CodeAnalyzer codeAnalyzer();
}