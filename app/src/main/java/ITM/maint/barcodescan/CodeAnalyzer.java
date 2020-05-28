package ITM.maint.barcodescan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.experimental.UseExperimental;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.Task;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

import ITM.maint.barcodescan.common.CameraReticleAnimator;
import ITM.maint.barcodescan.common.GraphicOverlay;
import ITM.maint.barcodescan.common.preferences.PreferenceUtils;

public class CodeAnalyzer implements ImageAnalysis.Analyzer {

    public static final String TAG = "CodeAnalyzer";
    private Context context;
    private Executor executor;
    private GraphicOverlay graphicOverlay;
    private final CameraReticleAnimator cameraReticleAnimator;
    private FirebaseVisionBarcodeDetector barcodeDetector;


    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public CodeAnalyzer(Context context, GraphicOverlay graphicOverlay,  Executor executor) {
        this.context = context;
        this.executor = executor;
        this.graphicOverlay = graphicOverlay;
        this.cameraReticleAnimator = new CameraReticleAnimator(graphicOverlay);
    }

    private int getFirebaseRotation(Context context) {
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                return FirebaseVisionImageMetadata.ROTATION_0;
            case Surface.ROTATION_90:
                return FirebaseVisionImageMetadata.ROTATION_90;
            case Surface.ROTATION_180:
                return FirebaseVisionImageMetadata.ROTATION_180;
            case Surface.ROTATION_270:
                return FirebaseVisionImageMetadata.ROTATION_270;
            default:
                throw new IllegalArgumentException(
                        "Invalid rotation value."
                );
        }
    }

    @Override
    @UseExperimental(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    public void analyze(@NonNull ImageProxy image) {
        if (image == null || image.getImage() == null) {
            return;
        }


        FirebaseVisionBarcodeDetectorOptions options =
                new FirebaseVisionBarcodeDetectorOptions.Builder()
                        .setBarcodeFormats(FirebaseVisionBarcode.FORMAT_ALL_FORMATS)
                        .build();
        barcodeDetector = FirebaseVision.getInstance().getVisionBarcodeDetector(options);

        FirebaseVisionImage visionImage = FirebaseVisionImage.fromMediaImage(image.getImage(), ImageFormat.YUV_420_888 );

        Task<List<FirebaseVisionBarcode>> task;
        task = barcodeDetector.detectInImage(visionImage);
        task.addOnSuccessListener(this.executor, barcodes -> {
                    if (!barcodes.isEmpty()) {
                        FirebaseVisionBarcode barcodeInCenter = null;
                        for (FirebaseVisionBarcode barcode : barcodes) {
                            RectF box = graphicOverlay.translateRect(barcode.getBoundingBox());
                            if (box.contains(graphicOverlay.getWidth() / 2f, graphicOverlay.getHeight() / 2f)) {
                                barcodeInCenter = barcode;
                                break;
                            }

                            graphicOverlay.clear();
                            if (barcodeInCenter == null) {
                                cameraReticleAnimator.start();
                                graphicOverlay.add(new BarcodeReticleGraphic(graphicOverlay, cameraReticleAnimator));
                            } else {
                                cameraReticleAnimator.cancel();
                                float sizeProgress =
                                        PreferenceUtils.getProgressToMeetBarcodeSizeRequirement(graphicOverlay, barcodeInCenter);
                                if (sizeProgress < 1) {
                                    // Barcode in the camera view is too small, so prompt user to move camera closer.
                                    graphicOverlay.add(new BarcodeConfirmingGraphic(graphicOverlay, barcodeInCenter));

                                } else {
                                    // Barcode size in the camera view is sufficient.
                                    if (PreferenceUtils.shouldDelayLoadingBarcodeResult(graphicOverlay.getContext())) {
                                        ValueAnimator loadingAnimator = createLoadingAnimator(graphicOverlay, barcodeInCenter);
                                        loadingAnimator.start();
                                        graphicOverlay.add(new BarcodeLoadingGraphic(graphicOverlay, loadingAnimator));
                                    }
                                }
                            }
                            graphicOverlay.invalidate();

                            Rect bounds = barcode.getBoundingBox();
                            Point[] corners = barcode.getCornerPoints();

                            String rawValue = barcode.getRawValue();

                            int valueType = barcode.getValueType();
                            // See API reference for complete list of supported types
                            switch (valueType) {
                                case FirebaseVisionBarcode.TYPE_WIFI:
                                    String ssid = barcode.getWifi().getSsid();
                                    String password = barcode.getWifi().getPassword();
                                    int type = barcode.getWifi().getEncryptionType();
                                    break;
                                case FirebaseVisionBarcode.TYPE_URL:
                                    String title = barcode.getUrl().getTitle();
                                    String url = barcode.getUrl().getUrl();
                                    break;
                            }
                        }
                    }
                        image.close();
                });
    }

    private ValueAnimator createLoadingAnimator(
            GraphicOverlay graphicOverlay, FirebaseVisionBarcode barcode) {
        float endProgress = 1.1f;
        ValueAnimator loadingAnimator = ValueAnimator.ofFloat(0f, endProgress);
        loadingAnimator.setDuration(2000);
        loadingAnimator.addUpdateListener(
                animation -> {
                    if (Float.compare((float) loadingAnimator.getAnimatedValue(), endProgress) >= 0) {
                        graphicOverlay.clear();
                    } else {
                        graphicOverlay.invalidate();
                    }
                });
        return loadingAnimator;
    }

    /*@Override
    protected void onFailure(Exception e) {
        Log.e(TAG, "Barcode detection failed!", e);
    }

    @Override
    public void stop() {
        try {
            barcodeDetector.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to close barcode detector!", e);
        }
    }*/
}




