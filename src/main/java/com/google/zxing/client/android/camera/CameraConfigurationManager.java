/*
 * Copyright (C) 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.Camera;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.google.zxing.client.android.PreferencesActivity;
import com.google.zxing.client.android.camera.open.CameraFacing;
import com.google.zxing.client.android.camera.open.OpenCamera;

import java.util.regex.Pattern;

/**
 * A class which deals with reading, parsing, and setting the camera parameters which are used to
 * configure the camera hardware.
 */
final class CameraConfigurationManager {

  private static final String TAG = "CameraConfiguration";

  private final Context context;
  private int cwNeededRotation;
  /**
   * 摄像头需要显示的角度
   */
  private int cwRotationFromDisplayToCamera;
  /**
   * 屏幕分辨率
   */
  private Point screenResolution;
  /**
   * 摄像头分辨率
   */
  private Point cameraResolution;
  /**
   * 最优的预览尺寸
   */
  private Point bestPreviewSize;
  /**
   * 摄像头在屏幕上的预览尺寸大小
   * 一般和屏幕分辨率一致
   */
  private Point previewSizeOnScreen;
  private int previewFormat;
  private String previewFormatString;
  CameraConfigurationManager(Context context) {
    this.context = context;
  }

  /**
   * Reads, one time, values from the camera that are needed by the app.
   */
  void initFromCameraParameters(OpenCamera camera) {
    Camera.Parameters parameters = camera.getCamera().getParameters();
    previewFormat = parameters.getPreviewFormat();
    previewFormatString = parameters.get("preview-format");
    WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    Display display = manager.getDefaultDisplay();

    int displayRotation = display.getRotation();
    //屏幕显示的自然角度即0,90,180,270
    int cwRotationFromNaturalToDisplay;
    switch (displayRotation) {
      case Surface.ROTATION_0:
        cwRotationFromNaturalToDisplay = 0;
        break;
      case Surface.ROTATION_90:
        cwRotationFromNaturalToDisplay = 90;
        break;
      case Surface.ROTATION_180:
        cwRotationFromNaturalToDisplay = 180;
        break;
      case Surface.ROTATION_270:
        cwRotationFromNaturalToDisplay = 270;
        break;
      default:
        // Have seen this return incorrect values like -90
        if (displayRotation % 90 == 0) {
          cwRotationFromNaturalToDisplay = (360 + displayRotation) % 360;
        } else {
          throw new IllegalArgumentException("Bad rotation: " + displayRotation);
        }
    }
    Log.i(TAG, "Display at: " + cwRotationFromNaturalToDisplay);
    //得到当前摄像头的自然角度即0，90，270
    int cwRotationFromNaturalToCamera = camera.getOrientation();
    Log.i(TAG, "Camera at: " + cwRotationFromNaturalToCamera);//90

    // Still not 100% sure about this. But acts like we need to flip this:
    if (camera.getFacing() == CameraFacing.FRONT) {
      cwRotationFromNaturalToCamera = (360 - cwRotationFromNaturalToCamera) % 360;
      Log.i(TAG, "Front camera overriden to: " + cwRotationFromNaturalToCamera);
    }

    /*
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    String overrideRotationString;
    if (camera.getFacing() == CameraFacing.FRONT) {
      overrideRotationString = prefs.getString(PreferencesActivity.KEY_FORCE_CAMERA_ORIENTATION_FRONT, null);
    } else {
      overrideRotationString = prefs.getString(PreferencesActivity.KEY_FORCE_CAMERA_ORIENTATION, null);
    }
    if (overrideRotationString != null && !"-".equals(overrideRotationString)) {
      Log.i(TAG, "Overriding camera manually to " + overrideRotationString);
      cwRotationFromNaturalToCamera = Integer.parseInt(overrideRotationString);
    }
     */

    cwRotationFromDisplayToCamera =
        (360 + cwRotationFromNaturalToCamera - cwRotationFromNaturalToDisplay) % 360;
    Log.i(TAG, "Final display orientation: " + cwRotationFromDisplayToCamera);
    if (camera.getFacing() == CameraFacing.FRONT) {
      Log.i(TAG, "Compensating rotation for front camera");
      cwNeededRotation = (360 - cwRotationFromDisplayToCamera) % 360;
    } else {
      cwNeededRotation = cwRotationFromDisplayToCamera;
    }
    Log.i(TAG, "Clockwise rotation from display to camera: " + cwNeededRotation);

    screenResolution = getDisplaySize(display);
    boolean isScreenPortrait = screenResolution.x < screenResolution.y;
    Log.i(TAG, "Screen resolution in current orientation: " + screenResolution);

    //changed here
    Point screenResolutionForCamera = new Point(screenResolution.x, screenResolution.y);
    if (isScreenPortrait) {
      //竖屏,因为竖屏时摄像头的分辨率为仍为横屏的数值eg.:(2560,1440)，而屏幕分辨率为(1440,2560)
      //所以要对调一下宽高以匹配摄像头的最佳分辨率
      screenResolutionForCamera.x = screenResolution.y;
      screenResolutionForCamera.y = screenResolution.x;
    }
    cameraResolution = CameraConfigurationUtils.findBestPreviewSizeValue(parameters, screenResolutionForCamera);
    Log.i(TAG, "Camera resolution: " + cameraResolution);//eg.Point(2560, 1440)
    //changed here:因为调用 findBestPreviewSizeValue方法参数一致，所以得到的值也会一致
//    bestPreviewSize = CameraConfigurationUtils.findBestPreviewSizeValue(parameters, screenResolution);
    bestPreviewSize = cameraResolution;
    Log.i(TAG, "Best available preview size: " + bestPreviewSize);//eg.Point(2560, 1440)

    boolean isPreviewSizePortrait = bestPreviewSize.x < bestPreviewSize.y;

    if (isScreenPortrait == isPreviewSizePortrait) {
      //屏幕方向与摄像头预览的方向一致，都为竖屏(isScreenPortrait=true && isPreviewSizePortrait=true)
      // 或者都为横屏isScreenPortrait=false && isPreviewSizePortrait=false
      previewSizeOnScreen = bestPreviewSize;
    }
    else {
      previewSizeOnScreen = new Point(bestPreviewSize.y, bestPreviewSize.x);
    }
    Log.i(TAG, "Preview size on screen: " + previewSizeOnScreen);
  }

  /**
   * 设置所期望的摄像头参数
   * @param camera
   * @param safeMode
   */
  void setDesiredCameraParameters(OpenCamera camera, boolean safeMode) {

    Camera theCamera = camera.getCamera();
    Camera.Parameters parameters = theCamera.getParameters();

    if (parameters == null) {
      Log.w(TAG, "Device error: no camera parameters are available. Proceeding without configuration.");
      return;
    }

    Log.i(TAG, "Initial camera parameters: " + parameters.flatten());

    if (safeMode) {
      Log.w(TAG, "In camera config safe mode -- most settings will not be honored");
    }

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

    initializeTorch(parameters, prefs, safeMode);

    CameraConfigurationUtils.setFocus(
        parameters,
        prefs.getBoolean(PreferencesActivity.KEY_AUTO_FOCUS, true),
        prefs.getBoolean(PreferencesActivity.KEY_DISABLE_CONTINUOUS_FOCUS, true),
        safeMode);

    if (!safeMode) {
      if (prefs.getBoolean(PreferencesActivity.KEY_INVERT_SCAN, false)) {
        CameraConfigurationUtils.setInvertColor(parameters);
      }

      if (!prefs.getBoolean(PreferencesActivity.KEY_DISABLE_BARCODE_SCENE_MODE, true)) {
        CameraConfigurationUtils.setBarcodeSceneMode(parameters);
      }

      if (!prefs.getBoolean(PreferencesActivity.KEY_DISABLE_METERING, true)) {
        CameraConfigurationUtils.setVideoStabilization(parameters);
        CameraConfigurationUtils.setFocusArea(parameters);
        CameraConfigurationUtils.setMetering(parameters);
      }

    }

//    parameters.setPreviewSize(bestPreviewSize.x, bestPreviewSize.y);
    parameters.setPreviewSize(cameraResolution.x, cameraResolution.y);

    theCamera.setParameters(parameters);

    theCamera.setDisplayOrientation(cwRotationFromDisplayToCamera);

    Camera.Parameters afterParameters = theCamera.getParameters();
    Camera.Size afterSize = afterParameters.getPreviewSize();
    if (afterSize != null && (bestPreviewSize.x != afterSize.width || bestPreviewSize.y != afterSize.height)) {
      //虽然上面设置了想要的摄像头预览尺寸，但是实际上真实的预览尺寸不是预期值(如摄像头不支持所设置的尺寸参数)
      Log.w(TAG, "Camera said it supported preview size " + bestPreviewSize.x + 'x' + bestPreviewSize.y +
          ", but after setting it, preview size is " + afterSize.width + 'x' + afterSize.height);
      bestPreviewSize.x = afterSize.width;
      bestPreviewSize.y = afterSize.height;
      //added here
      cameraResolution.x = afterSize.width;
      cameraResolution.y = afterSize.height;
    }
    //add here 设置相机预览为竖屏
//    theCamera.setDisplayOrientation(90);
  }

  Point getBestPreviewSize() {
    return bestPreviewSize;
  }

  Point getPreviewSizeOnScreen() {
    return previewSizeOnScreen;
  }

  Point getCameraResolution() {
    return cameraResolution;
  }

  Point getScreenResolution() {
    return screenResolution;
  }

  int getCWNeededRotation() {
    return cwNeededRotation;
  }

  boolean getTorchState(Camera camera) {
    if (camera != null) {
      Camera.Parameters parameters = camera.getParameters();
      if (parameters != null) {
        String flashMode = parameters.getFlashMode();
        return flashMode != null &&
            (Camera.Parameters.FLASH_MODE_ON.equals(flashMode) ||
             Camera.Parameters.FLASH_MODE_TORCH.equals(flashMode));
      }
    }
    return false;
  }

  void setTorch(Camera camera, boolean newSetting) {
    Camera.Parameters parameters = camera.getParameters();
    doSetTorch(parameters, newSetting, false);
    camera.setParameters(parameters);
  }

  private void initializeTorch(Camera.Parameters parameters, SharedPreferences prefs, boolean safeMode) {
    boolean currentSetting = FrontLightMode.readPref(prefs) == FrontLightMode.ON;
    doSetTorch(parameters, currentSetting, safeMode);
  }

  private void doSetTorch(Camera.Parameters parameters, boolean newSetting, boolean safeMode) {
    CameraConfigurationUtils.setTorch(parameters, newSetting);
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    if (!safeMode && !prefs.getBoolean(PreferencesActivity.KEY_DISABLE_EXPOSURE, true)) {
      CameraConfigurationUtils.setBestExposure(parameters, newSetting);
    }
  }

  public int getPreviewFormat() {
    return previewFormat;
  }

  public String getPreviewFormatString() {
    return previewFormatString;
  }

  @SuppressLint("NewApi")
  private Point getDisplaySize(final Display display) {
    Point newSizePoint = new Point();
    try {
      display.getSize(newSizePoint);
    } catch (Exception e) {
      newSizePoint.x = display.getWidth();
      newSizePoint.y = display.getHeight();
    }
    return newSizePoint;
  }
  private static final int TEN_DESIRED_ZOOM = 27;
  private static final float ZOOM_BASE = 10.0f;
  private static final int DESIRED_SHARPNESS = 30;
  private static final Pattern COMMA_PATTERN = Pattern.compile(",");
  public void setZoom(Camera.Parameters parameters) {
    setZoom(parameters, TEN_DESIRED_ZOOM);
  }

  /**
   * 当前摄像头是否支持缩放
   * @param curCameraParameter
   * @return
   */
  public boolean isCurCameraSupportZoom(Camera.Parameters curCameraParameter) {
    if (curCameraParameter != null) {
      return curCameraParameter.isZoomSupported();
    }
    return false;
  }
  /**
   * 根据当前摄像头的参数信息获取当前的缩放值
   * @param parameters
   * @return
   */
  public int getCameraCurZoom(Camera.Parameters parameters) {
    if (!isCurCameraSupportZoom(parameters)) {
      return 0;
    }
    String zoomString = parameters.get("zoom");
    if (zoomString != null) {
      try {
        int tenZoom = (int) (ZOOM_BASE * Double.parseDouble(zoomString));
        return tenZoom;
      } catch (NumberFormatException nfe) {
        Log.w(TAG, "Bad max-zoom: " + nfe.getMessage());
      }
    }
    return 0;
  }

  /**
   * 注意放大了10倍
   * @param parameters
   * @return
   */
  public int getCameraMaxZoom(Camera.Parameters parameters) {
    if (!isCurCameraSupportZoom(parameters)) {
      return 100;
    }
//    int maxZoom = parameters.getMaxZoom();
//    if (maxZoom > 0) {
//      return (int) (ZOOM_BASE * maxZoom);
//    }
    String zoomString = parameters.get("max-zoom");
    if (zoomString != null) {
      try {
        return (int) (ZOOM_BASE * Double.parseDouble(zoomString));
      } catch (NumberFormatException nfe) {
        Log.w(TAG, "Bad max-zoom: " + nfe.getMessage());
      }
    }
    return 100;
  }

  public void setZoom(Camera.Parameters parameters, int tenDesiredZoom) {
    if (!isCurCameraSupportZoom(parameters)) {
      return;
    }
    String maxZoomString = parameters.get("max-zoom");
    if (maxZoomString != null) {
      try {
        int tenMaxZoom = (int) (ZOOM_BASE * Double
                .parseDouble(maxZoomString));
        Log.w(TAG, "tenMaxZoom: " + tenMaxZoom);
        if (tenDesiredZoom > tenMaxZoom) {
          tenDesiredZoom = tenMaxZoom;
        }
      } catch (NumberFormatException nfe) {
        Log.w(TAG, "Bad max-zoom: " + nfe.getMessage());
      }
    }

    String takingPictureZoomMaxString = parameters
            .get("taking-picture-zoom-max");
    if (takingPictureZoomMaxString != null) {
      try {
        int tenMaxZoom = Integer.parseInt(takingPictureZoomMaxString);
        if (tenDesiredZoom > tenMaxZoom) {
          tenDesiredZoom = tenMaxZoom;
        }
      } catch (NumberFormatException nfe) {
        Log.w(TAG, "Bad taking-picture-zoom-max: " + nfe.getMessage());
      }
    }

    String motZoomValuesString = parameters.get("mot-zoom-values");
    if (motZoomValuesString != null) {
      tenDesiredZoom = findBestMotZoomValue(motZoomValuesString,
              tenDesiredZoom);
    }

    String motZoomStepString = parameters.get("mot-zoom-step");
    if (motZoomStepString != null) {
      try {
        double motZoomStep = Double.parseDouble(motZoomStepString
                .trim());
        int tenZoomStep = (int) (ZOOM_BASE * motZoomStep);
        if (tenZoomStep > 1) {
          tenDesiredZoom -= tenDesiredZoom % tenZoomStep;
        }
      } catch (NumberFormatException nfe) {
        Log.w(TAG, "motZoomStep: " + nfe.getMessage());
      }
    }

    // Set zoom. This helps encourage the user to pull back.
    // Some devices like the Behold have a zoom parameter
    if (maxZoomString != null || motZoomValuesString != null) {
      parameters.set("zoom", String.valueOf(tenDesiredZoom / ZOOM_BASE));
    }

    // Most devices, like the Hero, appear to expose this zoom parameter.
    // It takes on values like "27" which appears to mean 2.7x zoom
    if (takingPictureZoomMaxString != null) {
      parameters.set("taking-picture-zoom", tenDesiredZoom);
    }
  }
  private int findBestMotZoomValue(CharSequence stringValues,int tenDesiredZoom) {
    int tenBestValue = 0;
    for (String stringValue : COMMA_PATTERN.split(stringValues)) {
      stringValue = stringValue.trim();
      double value;
      try {
        value = Double.parseDouble(stringValue);
      } catch (NumberFormatException nfe) {
        return tenDesiredZoom;
      }
      int tenValue = (int) (ZOOM_BASE * value);
      if (Math.abs(tenDesiredZoom - value) < Math.abs(tenDesiredZoom
              - tenBestValue)) {
        tenBestValue = tenValue;
      }
    }
    return tenBestValue;
  }
}
