package ITM.maint.barcodescan;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AppExecutor {

    public static final String TAG = AppExecutor.class.getSimpleName();
    private final Executor analyzerThread;
    private final Executor detectorThread;
    private final Executor animationThread;
    private final Executor mainThread;

    public AppExecutor(Executor analyzerThread,
                       Executor detectorThread,
                       Executor animationThread,
                       Executor mainThread) {
        this.analyzerThread = analyzerThread;
        this.detectorThread = detectorThread;
        this.animationThread = animationThread;
        this.mainThread = mainThread;
    }

    @Inject
    public AppExecutor() {
        this(Executors.newFixedThreadPool(3), new AnimationThreadExecutor(), Executors.newFixedThreadPool(3),
                new MainThreadExecutor());
    }



    public Executor analyzerThread() {
        return analyzerThread;
    }

    public Executor detectorThread() {
        return detectorThread;
    }

    public Executor animationThread() { return animationThread; }

    public Executor mainThread() {
        return mainThread;
    }



    public static class MainThreadExecutor implements Executor {
        private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable command) {
            mainThreadHandler.post(command);
        }
    }

    public static class AnimationThreadExecutor implements Executor {
        private Handler animationThreadHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable command) {
            animationThreadHandler.post(command);
        }
    }
}
