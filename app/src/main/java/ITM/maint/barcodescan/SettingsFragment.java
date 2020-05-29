package ITM.maint.barcodescan;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;

import androidx.camera.core.Camera;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.util.DisplayMetrics;
import android.util.Size;

import ITM.maint.barcodescan.common.preferences.PreferenceUtils;

public class SettingsFragment extends PreferenceFragmentCompat {

    public static final float ASPECT_RATIO_TOLERANCE = 0.01f;
    private Camera camera;

    @Override
    public void onCreatePreferences(Bundle bundle, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        setUpRearCameraPreviewSizePreference();
    }

    @SuppressLint("RestrictedApi")
    private void setUpRearCameraPreviewSizePreference() {
        ListPreference previewSizePreference =
                (ListPreference) findPreference(getString(R.string.pref_key_rear_camera_preview_size));
        if (previewSizePreference == null) {
            return;
        }

        try {

            DisplayMetrics displayMetrics = new DisplayMetrics();
            this.getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            List<Size> previewSizeList = setupCamera( displayMetrics.widthPixels, displayMetrics.heightPixels);

            String[] previewSizeStringValues = new String[previewSizeList.size()];
            Map<String, String> previewToPictureSizeStringMap = new HashMap<>();
            for (int i = 0; i < previewSizeList.size(); i++) {
                Size sizePair = previewSizeList.get(i);
                previewSizeStringValues[i] = sizePair.toString();
            }
            previewSizePreference.setEntries(previewSizeStringValues);
            previewSizePreference.setEntryValues(previewSizeStringValues);
            previewSizePreference.setSummary(previewSizePreference.getEntry());
            previewSizePreference.setOnPreferenceChangeListener(
                    (preference, newValue) -> {
                        String newPreviewSizeStringValue = (String) newValue;
                        previewSizePreference.setSummary(newPreviewSizeStringValue);
                        PreferenceUtils.saveStringPreference(
                                getActivity(),
                                R.string.pref_key_rear_camera_picture_size,
                                previewToPictureSizeStringMap.get(newPreviewSizeStringValue));
                        return true;
                    });

        } catch (Exception e) {
            // If there's no camera for the given camera id, hide the corresponding preference.
            if (previewSizePreference.getParent() != null) {
                previewSizePreference.getParent().removePreference(previewSizePreference);
            }
        }
    }

    private List<Size> setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) this.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            for(String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                List<Size> collectorSizes = new ArrayList<>();
                for(Size option : map.getOutputSizes(SurfaceTexture.class)){
                    if(width > height) {
                        if(option.getWidth() > width &&
                                option.getHeight() > height) {
                            collectorSizes.add(option);
                        }
                    } else {
                        if(option.getWidth() > height &&
                                option.getHeight() > width) {
                            collectorSizes.add(option);
                        }
                    }
                }
                return collectorSizes;
            }
        } catch (CameraAccessException  e) {
            e.printStackTrace();
        }
        return null;
    }

    private Size getPreferredPreviewSize(Size[] mapSizes, int width, int height) {
        List<Size> collectorSizes = new ArrayList<>();
        for(Size option : mapSizes) {
            if(width > height) {
                if(option.getWidth() > width &&
                        option.getHeight() > height) {
                    collectorSizes.add(option);
                }
            } else {
                if(option.getWidth() > height &&
                        option.getHeight() > width) {
                    collectorSizes.add(option);
                }
            }
        }
        if(collectorSizes.size() > 0) {
            return Collections.min(collectorSizes, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                }
            });
        }
        return mapSizes[0];
    }

}
