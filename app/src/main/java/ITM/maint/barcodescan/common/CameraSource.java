// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package ITM.maint.barcodescan.common;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.google.android.gms.common.images.Size;


import java.io.IOException;
import java.lang.Thread.State;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import androidx.camera.core.CameraSelector;

import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;

import ITM.maint.barcodescan.common.preferences.PreferenceUtils;


/**
 * Manages the camera and allows UI updates on top of it (e.g. overlaying extra Graphics or
 * displaying extra information). This receives preview frames from the camera at a specified rate,
 * sending those frames to child classes' detectors / classifiers as fast as it is able to process.
 */
@SuppressLint("MissingPermission")
public class CameraSource {
    @SuppressLint("InlinedApi")
    public static final int CAMERA_FACING_BACK = CameraSelector.LENS_FACING_BACK;

    @SuppressLint("InlinedApi")
    public static final int CAMERA_FACING_FRONT = CameraSelector.LENS_FACING_BACK;

    public static final int IMAGE_FORMAT = ImageFormat.NV21;
    private static final int MIN_CAMERA_PREVIEW_WIDTH = 400;
    private static final int MAX_CAMERA_PREVIEW_WIDTH = 1300;
    public static final int DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH = 480;
    public static final int DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT = 360;

    private static final String TAG = "CameraSource";

    /**
     * The dummy surface texture must be assigned a chosen name. Since we never use an OpenGL context,
     * we can choose any ID we want here. The dummy surface texture is not a crazy hack - it is
     * actually how the camera team recommends using the camera without a preview.
     */
    private static final int DUMMY_TEXTURE_NAME = 100;

    /**
     * If the absolute difference between a preview size aspect ratio and a picture size aspect ratio
     * is less than this tolerance, they are considered to be the same aspect ratio.
     */
    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

    protected Activity activity;

    private int facing = CAMERA_FACING_BACK;

    /**
     * Rotation of the device, and thus the associated preview images captured from the device. See
     * Frame.Metadata#getRotation().
     */
    private int rotation;

    private Size previewSize;

    private final float requestedFps = 30.0f;
    private final boolean requestedAutoFocus = true;

    // These instances need to be held onto to avoid GC of their underlying resources.  Even though
    // these aren't used outside of the method that creates them, they still must have hard
    // references maintained to them.
    private SurfaceTexture dummySurfaceTexture;
    private final GraphicOverlay graphicOverlay;
    private Preview preview;
    private androidx.camera.core.Camera camera;

    // True if a SurfaceTexture is being used for the preview, false if a SurfaceHolder is being
    // used for the preview.  We want to be compatible back to Gingerbread, but SurfaceTexture
    // wasn't introduced until Honeycomb.  Since the interface cannot use a SurfaceTexture, if the
    // developer wants to display a preview we must use a SurfaceHolder.  If the developer doesn't
    // want to display a preview we use a SurfaceTexture if we are running at least Honeycomb.
    private boolean usingSurfaceTexture;


    private final Object processorLock = new Object();
    // @GuardedBy("processorLock")
    private VisionImageProcessor frameProcessor;

    /**
     * Map to convert between a byte array, received from the camera, and its associated byte buffer.
     * We use byte buffers internally because this is a more efficient way to call into native code
     * later (avoids a potential copy).
     *
     * <p><b>Note:</b> uses IdentityHashMap here instead of HashMap because the behavior of an array's
     * equals, hashCode and toString methods is both useless and unexpected. IdentityHashMap enforces
     * identity ('==') check on the keys.
     */
    private final Map<byte[], ByteBuffer> bytesToByteBuffer = new IdentityHashMap<>();

    public CameraSource(Activity activity, GraphicOverlay overlay, androidx.camera.core.Camera camera, Preview preview) {
        this.activity = activity;
        graphicOverlay = overlay;
        graphicOverlay.clear();
        this.camera = camera;
        this.preview = preview;
    }

    // ==============================================================================================
    // Public
    // ==============================================================================================

    /** Stops the camera and releases the resources of the camera and underlying detector. */
    public void release() {
        synchronized (processorLock) {
            stop();
            cleanScreen();

            if (frameProcessor != null) {
                frameProcessor.stop();
            }
        }
    }

    /**
     * Opens the camera and starts sending preview frames to the underlying detector. The preview
     * frames are not displayed.
     *
     * @throws IOException if the camera's preview texture or display could not be initialized
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.CAMERA)
    public synchronized CameraSource start() throws IOException {
        if (camera != null) {
            return this;
        }
        createCamera(camera);
        usingSurfaceTexture = true;

        return this;
    }

    /**
     * Closes the camera and stops sending frames to the underlying frame detector.
     *
     * <p>This camera source may be restarted again by calling {@link #start()} or {@link
     * #*start(SurfaceHolder)}.
     *
     * <p>Call {@link #release()} instead to completely shut down this camera source and release the
     * resources of the underlying detector.
     */
    public synchronized void stop() {

        if (camera != null) {

            //camera.setPreviewCallbackWithBuffer(null);
            //camera.release();
            camera = null;
        }

        // Release the reference to any image buffers, since these will no longer be in use.
        bytesToByteBuffer.clear();
    }

    /** Changes the facing of the camera. */
    public synchronized void setFacing(int facing) {
        if ((facing != CAMERA_FACING_BACK) && (facing != CAMERA_FACING_FRONT)) {
            throw new IllegalArgumentException("Invalid camera: " + facing);
        }
        this.facing = facing;
    }

    /** Returns the preview size that is currently in use by the underlying camera. */
    public Size getPreviewSize() {
        return previewSize;
    }

    /**
     * Returns the selected camera; one of {@link #CAMERA_FACING_BACK} or {@link
     * #CAMERA_FACING_FRONT}.
     */
    public int getCameraFacing() {
        return facing;
    }

    /**
     * Opens the camera and applies the user settings.
     *
     * @throws IOException if camera cannot be found or preview cannot be processed
     */
    @SuppressLint("InlinedApi")
    private void createCamera() throws IOException {

        setPreviewAndPictureSize();
        setRotation();

    }

    /**
     * Gets the id for the camera specified by the direction it is facing. Returns -1 if no such
     * camera was found.
     *
     * @param *facing the desired camera (front-facing or rear-facing)
     */
    private void setPreviewAndPictureSize() throws IOException {
        // Gives priority to the preview size specified by the user if exists.
        SizePair sizePair = PreferenceUtils.getUserSpecifiedPreviewSize(activity.getApplicationContext());
        if (sizePair == null) {
            // Camera preview size is based on the landscape mode, so we need to also use the aspect
            // ration of display in the same mode for comparison.
            float displayAspectRatioInLandscape;
            if (isPortraitMode(graphicOverlay.getContext())) {
                displayAspectRatioInLandscape =
                        (float) graphicOverlay.getHeight() / graphicOverlay.getWidth();
            } else {
                displayAspectRatioInLandscape =
                        (float) graphicOverlay.getWidth() / graphicOverlay.getHeight();
            }
            sizePair = selectSizePair(displayAspectRatioInLandscape);
        }
        if (sizePair == null) {
            throw new IOException("Could not find suitable preview size.");
        }

        previewSize = sizePair.preview;
        Log.v(TAG, "Camera preview size: " + previewSize);
        parameters.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
        PreferenceUtils.saveStringPreference(
                context, R.string.pref_key_rear_camera_preview_size, previewSize.toString());

        Size pictureSize = sizePair.picture;
        if (pictureSize != null) {
            Log.v(TAG, "Camera picture size: " + pictureSize);
            parameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
            PreferenceUtils.saveStringPreference(
                    context, R.string.pref_key_rear_camera_picture_size, pictureSize.toString());
        }
    }

    /**
     * Calculates the correct rotation for the given camera id and sets the rotation in the
     * parameters. It also sets the camera's display orientation and rotation.
     *
     * @param parameters the camera parameters for which to set the rotation.
     */
    private void setRotation(Camera camera, Camera.Parameters parameters) {
        WindowManager windowManager = (WindowManager) activity.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        int deviceRotation = windowManager.getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (deviceRotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e(TAG, "Bad device rotation value: " + deviceRotation);
        }

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(CAMERA_FACING_BACK, cameraInfo);
        int angle = (cameraInfo.orientation - degrees + 360) % 360;
        // This corresponds to the rotation constants in FirebaseVisionImageMetadata.
        this.rotation = angle / 90;
        camera.setDisplayOrientation(angle);
        parameters.setRotation(angle);
    }

    /**
     * Selects the most suitable preview and picture size, given the desired width and height.
     *
     * <p>Even though we only need to find the preview size, it's necessary to find both the preview
     * size and the picture size of the camera together, because these need to have the same aspect
     * ratio. On some hardware, if you would only set the preview size, you will get a distorted
     * image.
     *
     * @param *camera the camera to select a preview size from
     * @param *desiredWidth the desired width of the camera preview frames
     * @param *desiredHeight the desired height of the camera preview frames
     * @return the selected preview and picture size pair
     */
    private static SizePair selectSizePair(float displayAspectRatioInLandscape) {
        List<SizePair> validPreviewSizes = generateValidPreviewSizeList();

        SizePair selectedPair = null;
        // Picks the preview size that has closest aspect ratio to display view.
        float minAspectRatioDiff = Float.MAX_VALUE;
        for (SizePair sizePair : validPreviewSizes) {
            Size previewSize = sizePair.preview;
            if (previewSize.getWidth() < MIN_CAMERA_PREVIEW_WIDTH
                    || previewSize.getWidth() > MAX_CAMERA_PREVIEW_WIDTH) {
                continue;
            }

            float previewAspectRatio = (float) previewSize.getWidth() / previewSize.getHeight();
            float aspectRatioDiff = Math.abs(displayAspectRatioInLandscape - previewAspectRatio);
            if (Math.abs(aspectRatioDiff - minAspectRatioDiff) < Utils.ASPECT_RATIO_TOLERANCE) {
                if (selectedPair == null || selectedPair.preview.getWidth() < sizePair.preview.getWidth()) {
                    selectedPair = sizePair;
                }
            } else if (aspectRatioDiff < minAspectRatioDiff) {
                minAspectRatioDiff = aspectRatioDiff;
                selectedPair = sizePair;
            }
        }
    }

    /**
     * Stores a preview size and a corresponding same-aspect-ratio picture size. To avoid distorted
     * preview images on some devices, the picture size must be set to a size that is the same aspect
     * ratio as the preview size or the preview may end up being distorted. If the picture size is
     * null, then there is no picture size with the same aspect ratio as the preview size.
     */
    public static class SizePair {
        public final Size preview;
        @Nullable public final Size picture;

        SizePair(
                Camera.Size previewSize,
                @Nullable Camera.Size pictureSize) {
            preview = new Size(previewSize.width, previewSize.height);
            picture = pictureSize != null ? new Size(pictureSize.width, pictureSize.height) : null;
        }

        public SizePair(Size previewSize, @Nullable Size pictureSize) {
            preview = previewSize;
            picture = pictureSize;
        }
    }

    /**
     * Generates a list of acceptable preview sizes. Preview sizes are not acceptable if there is not
     * a corresponding picture size of the same aspect ratio. If there is a corresponding picture size
     * of the same aspect ratio, the picture size is paired up with the preview size.
     *
     * <p>This is necessary because even if we don't use still pictures, the still picture size must
     * be set to a size that is the same aspect ratio as the preview size we choose. Otherwise, the
     * preview images may be distorted on some devices.
     */
    public static List<SizePair> generateValidPreviewSizeList(Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        List<Camera.Size> supportedPreviewSizes =
                parameters.getSupportedPreviewSizes();
        List<Camera.Size> supportedPictureSizes =
                parameters.getSupportedPictureSizes();
        List<SizePair> validPreviewSizes = new ArrayList<>();
        for (Camera.Size previewSize : supportedPreviewSizes) {
            float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;

            // By looping through the picture sizes in order, we favor the higher resolutions.
            // We choose the highest resolution in order to support taking the full resolution
            // picture later.
            for (Camera.Size pictureSize : supportedPictureSizes) {
                float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
                if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                    validPreviewSizes.add(new SizePair(previewSize, pictureSize));
                    break;
                }
            }
        }

        // If there are no picture sizes with the same aspect ratio as any preview sizes, allow all
        // of the preview sizes and hope that the camera can handle it.  Probably unlikely, but we
        // still account for it.
        if (validPreviewSizes.size() == 0) {
            Log.w(TAG, "No preview sizes have a corresponding same-aspect-ratio picture size");
            for (Camera.Size previewSize : supportedPreviewSizes) {
                // The null picture size will let us know that we shouldn't set a picture size.
                validPreviewSizes.add(new SizePair(previewSize, null));
            }
        }

        return validPreviewSizes;
    }

    /**
     * Selects the most suitable preview frames per second range, given the desired frames per second.
     *
     * @param camera the camera to select a frames per second range from
     * @param desiredPreviewFps the desired frames per second for the camera preview frames
     * @return the selected preview frames per second range
     */
    @SuppressLint("InlinedApi")
    private static int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {
        // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
        // rates.
        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);

        // The method for selecting the best range is to minimize the sum of the differences between
        // the desired value and the upper and lower bounds of the range.  This may select a range
        // that the desired value is outside of, but this is often preferred.  For example, if the
        // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
        // range (15, 30).
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

    /**
     * Calculates the correct rotation for the given camera id and sets the rotation in the
     * parameters. It also sets the camera's display orientation and rotation.
     *
     * @param parameters the camera parameters for which to set the rotation
     * @param cameraId the camera id to set rotation based on
     */
    private void setRotation(Camera camera, Camera.Parameters parameters, int cameraId) {
        WindowManager windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        int degrees = 0;
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e(TAG, "Bad rotation value: " + rotation);
        }

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int angle;
        int displayAngle;
        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360;
            displayAngle = (360 - angle) % 360; // compensate for it being mirrored
        } else { // back-facing
            angle = (cameraInfo.orientation - degrees + 360) % 360;
            displayAngle = angle;
        }

        // This corresponds to the rotation constants.
        this.rotation = angle / 90;
        Log.d(TAG, "Display rotation is: " + rotation);
        Log.d(TAG, "Camera face is: " + cameraInfo.facing);
        Log.d(TAG, "Camera rotation is: " + cameraInfo.orientation);
        Log.d(TAG, "Rotation is: " + this.rotation);

        camera.setDisplayOrientation(displayAngle);
        parameters.setRotation(angle);
    }

    /**
     * Creates one buffer for the camera preview callback. The size of the buffer is based off of the
     * camera preview size and the format of the camera image.
     *
     * @return a new preview buffer of the appropriate size for the current camera settings
     */
    @SuppressLint("InlinedApi")
    private byte[] createPreviewBuffer(Size previewSize) {
        int bitsPerPixel = ImageFormat.getBitsPerPixel(IMAGE_FORMAT);
        long sizeInBits = (long) previewSize.getHeight() * previewSize.getWidth() * bitsPerPixel;
        int bufferSize = (int) Math.ceil(sizeInBits / 8.0d) + 1;

        // Creating the byte array this way and wrapping it, as opposed to using .allocate(),
        // should guarantee that there will be an array to work with.
        byte[] byteArray = new byte[bufferSize];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        if (!buffer.hasArray() || (buffer.array() != byteArray)) {
            // I don't think that this will ever happen.  But if it does, then we wouldn't be
            // passing the preview content to the underlying detector later.
            throw new IllegalStateException("Failed to create valid buffer for camera source.");
        }

        bytesToByteBuffer.put(byteArray, buffer);
        return byteArray;
    }

    // ==============================================================================================
    // Frame processing
    // ==============================================================================================

    /** Called when the camera has a new preview frame. */
    private class CameraPreviewCallback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            processingRunnable.setNextFrame(data, camera);
        }
    }

    public void setMachineLearningFrameProcessor(VisionImageProcessor processor) {
        synchronized (processorLock) {
            cleanScreen();
            if (frameProcessor != null) {
                frameProcessor.stop();
            }
            frameProcessor = processor;
        }
    }

    /**
     * This runnable controls access to the underlying receiver, calling it to process frames when
     * available from the camera. This is designed to run detection on frames as fast as possible
     * (i.e., without unnecessary context switching or waiting on the next frame).
     *
     * <p>While detection is running on a frame, new frames may be received from the camera. As these
     * frames come in, the most recent frame is held onto as pending. As soon as detection and its
     * associated processing is done for the previous frame, detection on the mostly recently received
     * frame will immediately start on the same thread.
     */
    private class FrameProcessingRunnable implements Runnable {

        // This lock guards all of the member variables below.
        private final Object lock = new Object();
        private boolean active = true;

        // These pending variables hold the state associated with the new frame awaiting processing.
        private ByteBuffer pendingFrameData;

        FrameProcessingRunnable() {}

        /**
         * Releases the underlying receiver. This is only safe to do after the associated thread has
         * completed, which is managed in camera source's release method above.
         */
        @SuppressLint("Assert")
        void release() {
            assert (processingThread.getState() == State.TERMINATED);
        }

        /** Marks the runnable as active/not active. Signals any blocked threads to continue. */
        void setActive(boolean active) {
            synchronized (lock) {
                this.active = active;
                lock.notifyAll();
            }
        }

        /**
         * Sets the frame data received from the camera. This adds the previous unused frame buffer (if
         * present) back to the camera, and keeps a pending reference to the frame data for future use.
         */
        void setNextFrame(byte[] data, Camera camera) {
            synchronized (lock) {
                if (pendingFrameData != null) {
                    camera.addCallbackBuffer(pendingFrameData.array());
                    pendingFrameData = null;
                }

                if (!bytesToByteBuffer.containsKey(data)) {
                    Log.d(
                            TAG,
                            "Skipping frame. Could not find ByteBuffer associated with the image "
                                    + "data from the camera.");
                    return;
                }

                pendingFrameData = bytesToByteBuffer.get(data);

                // Notify the processor thread if it is waiting on the next frame (see below).
                lock.notifyAll();
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames continuously.
         * The next pending frame is either immediately available or hasn't been received yet. Once it
         * is available, we transfer the frame info to local variables and run detection on that frame.
         * It immediately loops back for the next frame without pausing.
         *
         * <p>If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context switching
         * or frame acquisition time latency.
         *
         * <p>If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        @SuppressLint("InlinedApi")
        @SuppressWarnings("GuardedBy")
        @Override
        public void run() {
            ByteBuffer data;

            while (true) {
                synchronized (lock) {
                    while (active && (pendingFrameData == null)) {
                        try {
                            // Wait for the next frame to be received from the camera, since we
                            // don't have it yet.
                            lock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Frame processing loop terminated.", e);
                            return;
                        }
                    }

                    if (!active) {
                        // Exit the loop once this camera source is stopped or released.  We check
                        // this here, immediately after the wait() above, to handle the case where
                        // setActive(false) had been called, triggering the termination of this
                        // loop.
                        return;
                    }

                    // Hold onto the frame data locally, so that we can use this for detection
                    // below.  We need to clear pendingFrameData to ensure that this buffer isn't
                    // recycled back to the camera before we are done using that data.
                    data = pendingFrameData;
                    pendingFrameData = null;
                }

                // The code below needs to run outside of synchronization, because this will allow
                // the camera to add pending frame(s) while we are running detection on the current
                // frame.

                try {
                    synchronized (processorLock) {
                        Log.d(TAG, "Process an image");
                        frameProcessor.process(
                                data,
                                new FrameMetadata.Builder()
                                        .setWidth(previewSize.getWidth())
                                        .setHeight(previewSize.getHeight())
                                        .setRotation(rotation)
                                        .setCameraFacing(facing)
                                        .build(),
                                graphicOverlay);
                    }
                } catch (Exception t) {
                    Log.e(TAG, "Exception thrown from receiver.", t);
                } finally {
                    camera.addCallbackBuffer(data.array());
                }
            }
        }
    }

    /** Cleans up graphicOverlay and child classes can do their cleanups as well . */
    private void cleanScreen() {
        graphicOverlay.clear();
    }


    public static boolean isPortraitMode(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
    }
}
