package ITM.maint.barcodescan;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.experimental.UseExperimental;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.util.concurrent.Executor;


public class CodeAnalyzer implements ImageAnalysis.Analyzer {

    public static final String TAG = "CodeAnalyzer";
    private Context context;
    private Executor executor;


    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public CodeAnalyzer(Context context, Executor executor) {
        this.context = context;
        this.executor = executor;
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
                        .setBarcodeFormats(
                                FirebaseVisionBarcode.FORMAT_ALL_FORMATS)
                        .build();
        FirebaseVisionBarcodeDetector barcodeDetector = FirebaseVision.getInstance().getVisionBarcodeDetector(options);

        FirebaseVisionImage visionImage = FirebaseVisionImage.fromMediaImage(image.getImage(),getFirebaseRotation(this.context) );

        barcodeDetector.detectInImage(visionImage)
                .addOnSuccessListener(this.executor, barcodes -> {
                    if (!barcodes.isEmpty()) {
                        for (FirebaseVisionBarcode barcode : barcodes) {
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
}



