package ITM.maint.barcodescan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraX;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import dagger.android.support.DaggerAppCompatActivity;


public class TestActivity extends DaggerAppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    @Inject
    AppExecutor appExecutor;

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.test);

        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        openCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {

        Preview preview = new Preview.Builder().build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setImageQueueDepth(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();

        CodeAnalyzer codeAnalyzer = new CodeAnalyzer(this, appExecutor.detectorThread());
        imageAnalysis.setAnalyzer(appExecutor.analyzerThread(), codeAnalyzer);

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        //bind to lifecycle:
        Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

        PreviewView previewView=findViewById(R.id.preview_view);
        previewView.setImplementationMode(PreviewView.ImplementationMode.TEXTURE_VIEW);
        preview.setSurfaceProvider( previewView.getPreviewSurfaceProvider());


    }

    private void openCamera() {

        if (!isCameraPermissionGranted()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        } else {
            cameraProviderFuture.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                        bindPreview(cameraProvider);
                    } catch (ExecutionException | InterruptedException e) {
                        // No errors need to be handled for this Future
                        // This should never be reached
                        e.printStackTrace();
                    }
                }
            },appExecutor.mainThread());  //ContextCompat.getMainExecutor(this));



        }
    }

    private boolean isCameraPermissionGranted(){
        return ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openCamera();
                } else if (grantResults[0] == PackageManager.PERMISSION_DENIED){
                    // Should we show an explanation?
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.CAMERA)) {
                        //Show permission explanation dialog...
                    }else{
                        Toast.makeText(this, "Permission Disabled. Check Settings...Apps & notifications...App Permissions", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }
}
