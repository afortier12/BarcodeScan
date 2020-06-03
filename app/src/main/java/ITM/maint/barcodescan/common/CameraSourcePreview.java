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
public class CameraSourcePreview extends FrameLayout {
  private static final String TAG = "CameraSourcePreview";

  private static final int STATE_PREVIEW = 1;
  private static final int STATE_WAITING_LOCK = 2;
  private static final int STATE_WAITING_PRECAPTURE = 3;
  private static final int STATE_WAITING_NON_PRECAPTURE = 4;
  private static final int STATE_PICTURE_TAKEN = 5;

  private GraphicOverlay graphicOverlay;
  private AutoFitTextureView textureView;
  private boolean surfaceAvailable = false;
  private CameraSource cameraSource;
  private CameraDevice camera;
  private int state = STATE_PREVIEW;
  private Context context;





  public CameraSourcePreview(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    this.context = context;
  }

  public void attachCamera(CameraSource cameraSource) {
    this.cameraSource = cameraSource;

    textureView = new AutoFitTextureView(context);
    textureView.setSurfaceTextureListener(new surfaceTextureListerer());
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

  protected void createCameraPreviewSession(CameraDevice camera, Surface surface) {
      SurfaceTexture texture = textureView.getSurfaceTexture();
      assert texture != null;
      surface = new Surface(texture);
  }

  public void setAspectRatio(int width, int height) {
    textureView.setAspectRatio(width, height);
  }

  public void setTransform(Matrix matrix) {
    textureView.setTransform(matrix);
  }

  class surfaceTextureListerer implements TextureView.SurfaceTextureListener{

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
      cameraSource.openCamera(width, height);
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

}