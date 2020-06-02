/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ITM.maint.barcodescan.common;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Manages the camera and allows UI updates on top of it (e.g. overlaying extra Graphics). This
 * receives preview frames from the camera at a specified rate, sends those frames to detector as
 * fast as it is able to process.
 *
 * <p>This camera source makes a best effort to manage processing on preview frames as fast as
 * possible, while at the same time minimizing lag. As such, frames may be dropped if the detector
 * is unable to keep up with the rate of frames generated by the camera.
 */
public class CameraSource {

  public static final int CAMERA_FACING_BACK = CameraCharacteristics.LENS_FACING_BACK;

  private static final String TAG = "CameraSource";

  private static final int IMAGE_FORMAT = ImageFormat.NV21;
  private static final int MIN_CAMERA_PREVIEW_WIDTH = 400;
  private static final int MAX_CAMERA_PREVIEW_WIDTH = 1920;
  private static final int MAX_CAMERA_PREVIEW_HEIGHT = 1080;
  private static final int DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH = 640;
  private static final int DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT = 360;
  private static final float REQUESTED_CAMERA_FPS = 30.0f;

  private CameraSourcePreview previewView;
  private CameraDevice camera;
  private String cameraID;

  @FirebaseVisionImageMetadata.Rotation
  private int rotation;
  private Size previewSize;

  private final Semaphore processorLock = new Semaphore(1);
  private VisionImageProcessor frameProcessor;

  private final Map<byte[], ByteBuffer> bytesToByteBuffer = new IdentityHashMap<>();

  private final Context context;
  private final GraphicOverlay graphicOverlay;

  private Handler backgroundHandler;
  private HandlerThread backgroundThread;

  private final ImageReader.OnImageAvailableListener onImageAvailableListener
          = new ImageReader.OnImageAvailableListener() {

    @Override
    public void onImageAvailable(ImageReader reader) {
      //mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
    }

  };


  public CameraSource(GraphicOverlay graphicOverlay, CameraSourcePreview previewView) {
    this.context = graphicOverlay.getContext();
    this.graphicOverlay = graphicOverlay;
    this.previewView = previewView;
  }

  public void start() {
    if (previewView.isSurfaceAvailable()){
      previewView.start(getPreviewSize().getWidth(), getPreviewSize().getHeight());
    }
  }

  public void stop() {
    closeCamera();
    stopBackgroundThread();
  }


  /** Stops the camera and releases the resources of the camera and underlying detector. */
  public void release() {
    processorLock.release();
  }

  public void setFrameProcessor(VisionImageProcessor processor) {
    graphicOverlay.clear();
    synchronized (processorLock) {
      if (frameProcessor != null) {
        frameProcessor.stop();
      }
      frameProcessor = processor;
    }
  }

  public Handler getBackgroundHandler() {
    return backgroundHandler;
  }

  public void updateFlashMode(String flashMode) {
    //Parameters parameters = camera.getParameters();
    //parameters.setFlashMode(flashMode);
    //camera.setParameters(parameters);
  }

  private void closeCamera() {
    try {
      processorLock.acquire();
      previewView.stop();
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
    } finally {
      processorLock.release();
    }

  }

  public void openCamera(int width, int height, CameraDevice.StateCallback stateCallback) {

    setUpCameraOutputs(width, height);
    configureTransform(width, height);
    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    try {
      if (!processorLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Time out waiting to lock camera opening.");
      }
      if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        return;
      }
      startBackgroundThread();
      manager.openCamera(cameraID, stateCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
    }
  }

  /** Returns the preview size that is currently in use by the underlying camera. */
  Size getPreviewSize() {
    return previewSize;
  }

  static class CompareSizesByArea implements Comparator<Size> {

    @Override
    public int compare(Size lhs, Size rhs) {
      return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
              (long) rhs.getWidth() * rhs.getHeight());
    }

    @Override
    public Comparator<Size> reversed() {
      return null;
    }
  }

  private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                        int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
    List<Size> bigEnough = new ArrayList<>();
    List<Size> notBigEnough = new ArrayList<>();
    int w = aspectRatio.getWidth();
    int h = aspectRatio.getHeight();
    for (Size option : choices) {
      if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
              option.getHeight() == option.getWidth() * h / w) {
        if (option.getWidth() >= textureViewWidth &&
                option.getHeight() >= textureViewHeight) {
          bigEnough.add(option);
        } else {
          notBigEnough.add(option);
        }
      }
    }

    if (bigEnough.size() > 0) {
      return Collections.min(bigEnough, new CompareSizesByArea());
    } else if (notBigEnough.size() > 0) {
      return Collections.max(notBigEnough, new CompareSizesByArea());
    } else {
      return choices[0];
    }
  }

  private void setUpCameraOutputs(int width, int height) {
    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    try {
      for (String ID : manager.getCameraIdList()) {
        CameraCharacteristics characteristics
                = manager.getCameraCharacteristics(ID);

        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        cameraID = ID;

        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
          continue;
        }

        List imageSizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
        Size largest = Collections.max(imageSizes, new CompareSizesByArea());

        int displayRotation = ((Activity)context).getWindowManager().getDefaultDisplay().getRotation();
        rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean swappedDimensions = false;
        switch (displayRotation) {
          case Surface.ROTATION_0:
          case Surface.ROTATION_180:
            if (rotation == 90 || rotation == 270) {
              swappedDimensions = true;
            }
            break;
          case Surface.ROTATION_90:
          case Surface.ROTATION_270:
            if (rotation == 0 || rotation == 180) {
              swappedDimensions = true;
            }
            break;
        }

        Point displaySize = new Point();
        ((Activity)context).getWindowManager().getDefaultDisplay().getSize(displaySize);
        int rotatedPreviewWidth = width;
        int rotatedPreviewHeight = height;
        int maxPreviewWidth = displaySize.x;
        int maxPreviewHeight = displaySize.y;

        if (swappedDimensions) {
          rotatedPreviewWidth = height;
          rotatedPreviewHeight = width;
          maxPreviewWidth = displaySize.y;
          maxPreviewHeight = displaySize.x;
        }

        if (maxPreviewWidth > MAX_CAMERA_PREVIEW_WIDTH) {
          maxPreviewWidth = MAX_CAMERA_PREVIEW_WIDTH;
        }

        if (maxPreviewHeight > MAX_CAMERA_PREVIEW_HEIGHT) {
          maxPreviewHeight = MAX_CAMERA_PREVIEW_HEIGHT;
        }

        previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                maxPreviewHeight, largest);

        int orientation = context.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          previewView.setAspectRatio(
                  previewView.getWidth(), previewSize.getHeight());
        } else {
          previewView.setAspectRatio(
                  previewSize.getHeight(), previewSize.getWidth());
        }

        return;
      }
    } catch (CameraAccessException e) {
      e.printStackTrace();
    } catch (NullPointerException e) {
    }
  }


  private void configureTransform(int viewWidth, int viewHeight) {
    Activity activity = (Activity) context;
    if (null == previewView || null == previewSize || null == activity) {
      return;
    }
    int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
    Matrix matrix = new Matrix();
    RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
    float centerX = viewRect.centerX();
    float centerY = viewRect.centerY();
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      float scale = Math.max(
              (float) viewHeight / previewSize.getHeight(),
              (float) viewWidth / previewSize.getWidth());
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(90 * (rotation - 2), centerX, centerY);
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180, centerX, centerY);
    }
    previewView.setTransform(matrix);
  }

  /**
   * Starts a background thread and its {@link Handler}.
   */
  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("CameraBackground");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  /**
   * Stops the background thread and its {@link Handler}.
   */
  private void stopBackgroundThread() {
    if (backgroundThread != null) {
      backgroundThread.quitSafely();
      try {
        backgroundThread.join();
        backgroundThread = null;
        backgroundHandler = null;
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }



}
