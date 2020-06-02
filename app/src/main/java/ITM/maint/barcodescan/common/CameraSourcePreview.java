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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;

import ITM.maint.barcodescan.R;

/** Preview the camera image in the screen. */
public class CameraSourcePreview extends FrameLayout implements TextureView.SurfaceTextureListener {
  private static final String TAG = "CameraSourcePreview";

  private static final int STATE_PREVIEW = 0;
  private static final int STATE_WAITING_LOCK = 1;
  private static final int STATE_WAITING_PRECAPTURE = 2;
  private static final int STATE_WAITING_NON_PRECAPTURE = 3;
  private static final int STATE_PICTURE_TAKEN = 4;

  private GraphicOverlay graphicOverlay;
  private AutoFitTextureView textureView;
  private boolean surfaceAvailable = false;
  private CameraSource cameraSource;
  private CameraDevice camera;
  private CaptureRequest.Builder previewRequestBuilder;
  private CaptureRequest previewRequest;
  private CameraCaptureSession cameraCaptureSession;
  private Handler backgroundHandler;
  private int mState = STATE_PREVIEW;

  private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

    @Override
    public void onOpened(@NonNull CameraDevice cameraDevice) {
      cameraSource.release();
      camera = cameraDevice;
      backgroundHandler = cameraSource.getBackgroundHandler();
      createCameraPreviewSession(camera, backgroundHandler);
    }

    @Override
    public void onDisconnected(@NonNull CameraDevice cameraDevice) {
      cameraSource.release();
      cameraDevice.close();
      camera = null;
      backgroundHandler = null;
    }

    @Override
    public void onError(@NonNull CameraDevice cameraDevice, int error) {
      cameraSource.release();
      cameraDevice.close();
      camera = null;
      backgroundHandler = null;
    }

  };

  private CameraCaptureSession.CaptureCallback mCaptureCallback
          = new CameraCaptureSession.CaptureCallback() {

    private void process(CaptureResult result) {
      switch (mState) {
        case STATE_PREVIEW: {
          // We have nothing to do when the camera preview is working normally.
          break;
        }
        case STATE_WAITING_LOCK: {
          Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
          if (afState == null) {
            //captureStillPicture();
          } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                  CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
            // CONTROL_AE_STATE can be null on some devices
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
              mState = STATE_PICTURE_TAKEN;
              //captureStillPicture();
            } else {
              //runPrecaptureSequence();
            }
          }
          break;
        }
        case STATE_WAITING_PRECAPTURE: {
          // CONTROL_AE_STATE can be null on some devices
          Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
          if (aeState == null ||
                  aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                  aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
            mState = STATE_WAITING_NON_PRECAPTURE;
          }
          break;
        }
        case STATE_WAITING_NON_PRECAPTURE: {
          // CONTROL_AE_STATE can be null on some devices
          Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
          if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
            mState = STATE_PICTURE_TAKEN;
            //captureStillPicture();
          }
          break;
        }
      }
    }

    @Override
    public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureResult partialResult) {
      process(partialResult);
    }

    @Override
    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                   @NonNull CaptureRequest request,
                                   @NonNull TotalCaptureResult result) {
      process(result);
    }

  };


  public CameraSourcePreview(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);

    textureView = new AutoFitTextureView(context);
    textureView.setSurfaceTextureListener(this);
    addView(textureView);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    graphicOverlay = findViewById(R.id.camera_preview_graphic_overlay);
  }

  public boolean isSurfaceAvailable() {
    return surfaceAvailable;
  }

  protected void start(int width, int height){
      cameraSource.openCamera(width, height, stateCallback);

  }

  protected void stop() {

    if (null != cameraCaptureSession) {
      cameraCaptureSession.close();
      cameraCaptureSession = null;
    }
    if (null != cameraCaptureSession) {
      cameraCaptureSession.close();
      cameraCaptureSession = null;
    }

  }

  protected void createCameraPreviewSession(CameraDevice camera, Handler backgroundHandler) {
    try {
      SurfaceTexture texture = textureView.getSurfaceTexture();
      assert texture != null;
      Surface surface = new Surface(texture);

      previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      previewRequestBuilder.addTarget(surface);
      camera.createCaptureSession(Arrays.asList(surface),
              new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession captureSession) {
                  if (null == camera) {
                    return;
                  }

                  cameraCaptureSession = captureSession;
                  try {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                    previewRequest = previewRequestBuilder.build();
                    cameraCaptureSession.setRepeatingRequest(previewRequest,
                            null, backgroundHandler);
                  } catch (CameraAccessException e) {
                    e.printStackTrace();
                  }
                }

                @Override
                public void onConfigureFailed(
                        @NonNull CameraCaptureSession cameraCaptureSession) {
                }
              }, null
      );
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }

  public void setAspectRatio(int width, int height){
    textureView.setAspectRatio(width, height);
  }

  public void setTransform(Matrix matrix){
    textureView.setTransform(matrix);
  }


  @Override
  public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
    cameraSource.openCamera(width, height, stateCallback);
    surfaceAvailable = true;
  }

  @Override
  public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

  }

  @Override
  public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
    surfaceAvailable = false;
    return false;
  }

  @Override
  public void onSurfaceTextureUpdated(SurfaceTexture surface) {

  }

}