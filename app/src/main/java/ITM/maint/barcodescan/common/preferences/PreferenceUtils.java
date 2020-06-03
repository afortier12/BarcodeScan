package ITM.maint.barcodescan.common.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.gms.common.images.Size;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;

import ITM.maint.barcodescan.R;
import ITM.maint.barcodescan.common.CameraSource;
import ITM.maint.barcodescan.common.GraphicOverlay;
import ITM.maint.barcodescan.common.CameraSource;



/** Utility class to retrieve shared preferences. */
public class PreferenceUtils {

    static void saveString(Context context, @StringRes int prefKeyId, @Nullable String value) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getString(prefKeyId), value)
                .apply();
    }



    public static RectF getBarcodeReticleBox(GraphicOverlay overlay) {
        Context context = overlay.getContext();
        float overlayWidth = overlay.getWidth();
        float overlayHeight = overlay.getHeight();
        float boxWidth =
                overlayWidth * getIntPref(context, R.string.pref_key_barcode_reticle_width, 80) / 100;
        float boxHeight =
                overlayHeight * getIntPref(context, R.string.pref_key_barcode_reticle_height, 35) / 100;
        float cx = overlayWidth / 2;
        float cy = overlayHeight / 2;
        return new RectF(cx - boxWidth / 2, cy - boxHeight / 2, cx + boxWidth / 2, cy + boxHeight / 2);
    }

    public static boolean shouldDelayLoadingBarcodeResult(Context context) {
        return getBooleanPref(context, R.string.pref_key_delay_loading_barcode_result, true);
    }

    private static int getIntPref(Context context, @StringRes int prefKeyId, int defaultValue) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String prefKey = context.getString(prefKeyId);
        return sharedPreferences.getInt(prefKey, defaultValue);
    }

    private static boolean getBooleanPref(
            Context context, @StringRes int prefKeyId, boolean defaultValue) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String prefKey = context.getString(prefKeyId);
        return sharedPreferences.getBoolean(prefKey, defaultValue);
    }

    public static float getProgressToMeetBarcodeSizeRequirement(
            GraphicOverlay overlay, FirebaseVisionBarcode barcode) {
        Context context = overlay.getContext();
        if (getBooleanPref(context, R.string.pref_key_enable_barcode_size_check, false)) {
            float reticleBoxWidth = getBarcodeReticleBox(overlay).width();
            float barcodeWidth = overlay.translateX(barcode.getBoundingBox().width());
            float requiredWidth =
                    reticleBoxWidth * getIntPref(context, R.string.pref_key_minimum_barcode_width, 50) / 100;
            return Math.min(barcodeWidth / requiredWidth, 1);
        } else {
            return 1;
        }
    }

    public static void saveStringPreference(
            Context context, @StringRes int prefKeyId, @Nullable String value) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getString(prefKeyId), value)
                .apply();
    }



}