/*
 * Copyright (C) 2008 ZXing authors
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

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.client.android.R;
import com.google.zxing.client.android.camera.open.OpenCamera;
import com.google.zxing.client.android.camera.open.OpenCameraInterface;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * This object wraps the Camera service object and expects to be the only one talking to it. The
 * implementation encapsulates the steps needed to take preview-sized images, which are used for
 * both preview and decoding.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class CameraManager {

    private static final String TAG = CameraManager.class.getSimpleName();

    private static final int MIN_FRAME_WIDTH = 240;
    private static final int MIN_FRAME_HEIGHT = 240;
    private static final int MAX_FRAME_WIDTH = 1200; // = 5/8 * 1920
    private static final int MAX_FRAME_HEIGHT = 675; // = 5/8 * 1080

    private final Context context;
    private final CameraConfigurationManager configManager;
    private OpenCamera camera;
    private AutoFocusManager autoFocusManager;
    private Rect framingRect;
    private Rect framingRectInPreview;
    private boolean initialized;
    private boolean previewing;
    /**
     * 用户想请求开启的目标摄像头的ID，为前置/后置摄像头的ID
     */
    private int requestedCameraId = OpenCameraInterface.NO_REQUESTED_CAMERA;
    private int requestedFramingRectWidth;
    private int requestedFramingRectHeight;
    /**
     * Preview frames are delivered here, which we pass on to the registered handler. Make sure to
     * clear the handler so it will only receive one message.
     */
    private final PreviewCallback previewCallback;

    public CameraManager(Context context) {
        this.context = context;
        this.configManager = new CameraConfigurationManager(context);
        previewCallback = new PreviewCallback(configManager);
    }

    /**
     * Opens the camera driver and initializes the hardware parameters.
     *
     * @param holder The surface object which the camera will draw preview frames into.
     * @throws IOException Indicates the camera driver failed to open.
     */
    public synchronized void openDriver(SurfaceHolder holder) throws IOException {
        OpenCamera theCamera = camera;
        if (theCamera == null) {
            theCamera = OpenCameraInterface.open(requestedCameraId);
            if (theCamera == null) {
                throw new IOException("Camera.open() failed to return object from driver");
            }
            camera = theCamera;
        }

        if (!initialized) {
            initialized = true;
            configManager.initFromCameraParameters(theCamera);
            if (requestedFramingRectWidth > 0 && requestedFramingRectHeight > 0) {
                setManualFramingRect(requestedFramingRectWidth, requestedFramingRectHeight);
                requestedFramingRectWidth = 0;
                requestedFramingRectHeight = 0;
            }
        }

        Camera cameraObject = theCamera.getCamera();
        Camera.Parameters parameters = cameraObject.getParameters();
        String parametersFlattened = parameters == null ? null : parameters.flatten(); // Save these, temporarily
        try {
            configManager.setDesiredCameraParameters(theCamera, false);
        } catch (RuntimeException re) {
            // Driver failed
            Log.w(TAG, "Camera rejected parameters. Setting only minimal safe-mode parameters");
            Log.i(TAG, "Resetting to saved camera params: " + parametersFlattened);
            // Reset:
            if (parametersFlattened != null) {
                parameters = cameraObject.getParameters();
                parameters.unflatten(parametersFlattened);
                try {
                    cameraObject.setParameters(parameters);
                    configManager.setDesiredCameraParameters(theCamera, true);
                } catch (RuntimeException re2) {
                    // Well, darn. Give up
                    Log.w(TAG, "Camera rejected even safe-mode parameters! No configuration");
                }
            }
        }
        cameraObject.setPreviewDisplay(holder);
    }

    public synchronized boolean isOpen() {
        return camera != null;
    }

    /**
     * Closes the camera driver if still in use.
     */
    public synchronized void closeDriver() {
        if (camera != null) {
            camera.getCamera().release();
            camera = null;
            // Make sure to clear these each time we close the camera, so that any scanning rect
            // requested by intent is forgotten.
            framingRect = null;
            framingRectInPreview = null;
        }
    }

    /**
     * Asks the camera hardware to begin drawing preview frames to the screen.
     */
    public synchronized void startPreview() {
        OpenCamera theCamera = camera;
        if (theCamera != null && !previewing) {
            theCamera.getCamera().startPreview();
            previewing = true;
            autoFocusManager = new AutoFocusManager(context, theCamera.getCamera());
        }
    }

    /**
     * Tells the camera to stop drawing preview frames.
     */
    public synchronized void stopPreview() {
        if (autoFocusManager != null) {
            autoFocusManager.stop();
            autoFocusManager = null;
        }
        if (camera != null && previewing) {
            camera.getCamera().stopPreview();
            previewCallback.setHandler(null, 0);
            previewing = false;
        }
    }

    /**
     * Convenience method for {@link com.google.zxing.client.android.CaptureActivity2}
     *
     * @param newSetting if {@code true}, light should be turned on if currently off. And vice versa.
     */
    public synchronized void setTorch(boolean newSetting) {
        OpenCamera theCamera = camera;
        if (theCamera != null) {
            if (newSetting != configManager.getTorchState(theCamera.getCamera())) {
                boolean wasAutoFocusManager = autoFocusManager != null;
                if (wasAutoFocusManager) {
                    autoFocusManager.stop();
                    autoFocusManager = null;
                }
                configManager.setTorch(theCamera.getCamera(), newSetting);
                if (wasAutoFocusManager) {
                    autoFocusManager = new AutoFocusManager(context, theCamera.getCamera());
                    autoFocusManager.start();
                }
            }
        }
    }

    /**
     * A single preview frame will be returned to the handler supplied. The data will arrive as byte[]
     * in the message.obj field, with width and height encoded as message.arg1 and message.arg2,
     * respectively.
     *
     * @param handler The handler to send the message to.
     * @param message The what field of the message to be sent.
     */
    public synchronized void requestPreviewFrame(Handler handler, int message) {
        OpenCamera theCamera = camera;
        if (theCamera != null && previewing) {
            previewCallback.setHandler(handler, message);
            theCamera.getCamera().setOneShotPreviewCallback(previewCallback);
        }
    }

//  /**
//   * Calculates the framing rect which the UI should draw to show the user where to place the
//   * barcode. This target helps with alignment as well as forces the user to hold the device
//   * far enough away to ensure the image will be in focus.
//   *
//   * @return The rectangle to draw on screen in window coordinates.
//   */
//  public synchronized Rect getFramingRect() {
//    if (framingRect == null) {
//      if (camera == null) {
//        return null;
//      }
//      Point screenResolution = configManager.getScreenResolution();
//      if (screenResolution == null) {
//        // Called early, before init even finished
//        return null;
//      }
//
//      int width = findDesiredDimensionInRange(screenResolution.x, MIN_FRAME_WIDTH, MAX_FRAME_WIDTH);
//      int height = findDesiredDimensionInRange(screenResolution.y, MIN_FRAME_HEIGHT, MAX_FRAME_HEIGHT);
//
//      int leftOffset = (screenResolution.x - width) / 2;
//      int topOffset = (screenResolution.y - height) / 2;
//      framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
//      Log.d(TAG, "Calculated framing rect: " + framingRect);
//    }
//    return framingRect;
//  }

    int defFramingStyleId = R.style.viewfinder_outline_style;

    /**
     * @param newRectStyleResId
     */
    public void changeViewfinderRectStyle(int newRectStyleResId) {
        defFramingStyleId = newRectStyleResId;
    }

    /**
     * Calculates the framing rect which the UI should draw to show the user
     * where to place the barcode. This target helps with alignment as well as
     * forces the user to hold the device far enough away to ensure the image
     * will be in focus.
     * 计算出取景框(将目标二维码扫描进的显示框)的尺寸大小
     *
     * @return The rectangle to draw on screen in window coordinates.
     */
    public Rect getFramingRect() {
        Point screenResolution = configManager.getScreenResolution();
        if (framingRect == null) {
            if (camera == null) {
                return null;
            }
            TypedArray a = context.obtainStyledAttributes(null, R.styleable.Scanner, 0, defFramingStyleId);
            int width = a.getInt(R.styleable.Scanner_viewfinder_width, 180);
            int height = a.getInt(R.styleable.Scanner_viewfinder_height, 180);
            int resbase = a.getInt(R.styleable.Scanner_scale_base_resolution, 0);
            int left = a.getInt(R.styleable.Scanner_viewfinder_left, 0);
            int top = a.getInt(R.styleable.Scanner_viewfinder_top, 0);
            boolean mIsFullScreen = a.getBoolean(R.styleable.Scanner_viewfinder_is_fullscreen, false);
            //def style:width= 440,heigth=440,left=0,top=150
            Log.i("info", TAG + "-->getFramingRect() style:width= " + width + ",heigth=" + height + ",left=" + left + ",top=" + top);
            a.recycle();
            if (resbase == 0) {
                framingRect = getCommonBestViewfinderRect(screenResolution);
            }
            else {
                //根据基准分辨率来取最佳
                double ratio = 1;
                if (resbase > 0) {
                    ratio = screenResolution.x * 1.00 / resbase;
                }
                int newWidth = (int) (width * ratio);
                left = (int) (left * ratio);
                if (newWidth >= screenResolution.x) {
                    newWidth = screenResolution.x - 80;
                }
                if (left <= 0 || ((left + newWidth) >= screenResolution.x)) {
                    left = (screenResolution.x - newWidth) / 2;
                }

                int newHeight = (int) (height * ratio);
                if (newHeight >= screenResolution.y) {
                    newHeight = screenResolution.y - 100;
                }
                top = (int) (top * ratio);
                if (top <= 0) {//代表居中
                    int mStatusBarHeight = getStatusBarHeight(context);
                    if (mIsFullScreen) {
                        mStatusBarHeight = 0;
                    }
                    top = (screenResolution.y - newHeight) / 2 - mStatusBarHeight / 2;
                }

                if ((top + newHeight) >= screenResolution.y) {
                    top = (screenResolution.y - newHeight) / 2;
                }
                framingRect = new Rect(left, top, left + newWidth, top + newHeight);
                Log.d("info", TAG + "->getFramingRect() framingRect=" + framingRect);
            }
        }
        return framingRect;
    }

    private Rect getCommonBestViewfinderRect(Point screenResolution) {
        if (screenResolution != null) {
            float curDensity = getCurDensity();
            Log.e("info", TAG + "--> getCommonBestViewfinderRect()curDensity= " + curDensity);
            int viewfinderW = 100;
            int viewfinderH = viewfinderW;
            int top = (int) ((100 + 10) * curDensity);
            int left = 0;
            //注意两点：1、取景框(正方形)的边长永远取决于屏幕的最短的一条边; 2、left的起点只与x轴有关
            int minSideLength = Math.min(screenResolution.x, (screenResolution.y - top));//因为y轴方向总是要减去title的高度的
            int retractDistanceBaseDensity = (int) (100 * curDensity);
            viewfinderW = viewfinderH = minSideLength - retractDistanceBaseDensity;//让宽向内缩进100

            left = (screenResolution.x - viewfinderW) / 2;
//            if (screenResolution.x < screenResolution.y) {//竖屏,则取景框的宽取决于最短的边即x,但因为为绘制正方形的取景框
//                left = (screenResolution.x - viewfinderW) / 2;
//            }
//            else{//横屏,则取景框的高取决于y,但因为要绘制正方形的取景框，则宽高一致
//                left = (screenResolution.x - viewfinderW) / 2;
//            }
            return new Rect(left, top, left + viewfinderW, top + viewfinderH);
        }
        return null;
    }

    private static int findDesiredDimensionInRange(int resolution, int hardMin, int hardMax) {
        int dim = 5 * resolution / 8; // Target 5/8 of each dimension
        if (dim < hardMin) {
            return hardMin;
        }
        if (dim > hardMax) {
            return hardMax;
        }
        return dim;
    }

    /**
     * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
     * not UI / screen.
     *
     * @return {@link Rect} expressing barcode scan area in terms of the preview size
     */
    public synchronized Rect getFramingRectInPreview() {
        if (framingRectInPreview == null) {
            Rect framingRect = getFramingRect();
            if (framingRect == null) {
                return null;
            }
            Rect rect = new Rect(framingRect);
//    cameraResolution= Point(2560, 1440) screenResolution=Point(1440, 2560)
            Point cameraResolution = configManager.getCameraResolution();
            Point screenResolution = configManager.getScreenResolution();
            if (cameraResolution == null || screenResolution == null) {
                // Called early, before init even finished
                return null;
            }
            Log.i("info", TAG + "--> getFramingRectInPreview()cameraResolution= " + cameraResolution + " screenResolution=" + screenResolution);
//      rect.left = rect.left * cameraResolution.x / screenResolution.x;
//      rect.right = rect.right * cameraResolution.x / screenResolution.x;
//      rect.top = rect.top * cameraResolution.y / screenResolution.y;
//      rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
            framingRectInPreview = rect;
        }
        return framingRectInPreview;
    }


    /**
     * Allows third party apps to specify the camera ID, rather than determine
     * it automatically based on available cameras and their orientation.
     *
     * @param cameraId camera ID of the camera to use. A negative value means "no preference".
     */
    public synchronized void setManualCameraId(int cameraId) {
        requestedCameraId = cameraId;
    }

    /**
     * Allows third party apps to specify the scanning rectangle dimensions, rather than determine
     * them automatically based on screen resolution.
     *
     * @param width  The width in pixels to scan.
     * @param height The height in pixels to scan.
     */
    public synchronized void setManualFramingRect(int width, int height) {
        if (initialized) {
            Point screenResolution = configManager.getScreenResolution();
            if (width > screenResolution.x) {
                width = screenResolution.x;
            }
            if (height > screenResolution.y) {
                height = screenResolution.y;
            }
            int leftOffset = (screenResolution.x - width) / 2;
            int topOffset = (screenResolution.y - height) / 2;
            framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
            Log.d(TAG, "Calculated manual framing rect: " + framingRect);
            framingRectInPreview = null;
        } else {
            requestedFramingRectWidth = width;
            requestedFramingRectHeight = height;
        }
    }

    /**
     * A factory method to build the appropriate LuminanceSource object based on the format
     * of the preview buffers, as described by Camera.Parameters.
     * 依据摄像头预览的图像数据byte[]data 构建出 Planar（平面）YUV(亮度和色差信号)源信息
     *
     * @param data   A preview frame. @see {@link PreviewCallback#onPreviewFrame(byte[], Camera)}
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return A PlanarYUVLuminanceSource instance.
     */
    public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = getFramingRectInPreview();
        if (rect == null) {
            return null;
        }
//    Log.e("info", TAG + "--->buildLuminanceSource() width =" + width + " height = " + height + " framingRect" + rect);
        // Go ahead and assume it's YUV rather than die.
        return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                rect.width(), rect.height(), false);
    }
//
//  /**
//   * A factory method to build the appropriate LuminanceSource object based on
//   * the format of the preview buffers, as described by Camera.Parameters.
//   *
//   * @param data
//   *            A preview frame.
//   * @param width
//   *            The width of the image.
//   * @param height
//   *            The height of the image.
//   * @return A PlanarYUVLuminanceSource instance.
//   */
//  public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data,
//                                                       int width, int height) {
//    Rect rect = getFramingRectInPreview();
//    int previewFormat = configManager.getPreviewFormat();
//    String previewFormatString = configManager.getPreviewFormatString();
//    switch (previewFormat) {
//      // This is the standard Android format which all devices are REQUIRED to
//      // support.
//      // In theory, it's the only one we should ever care about.
//      case PixelFormat.YCbCr_420_SP:
//        // This format has never been seen in the wild, but is compatible as
//        // we only care
//        // about the Y channel, so allow it.
//      case PixelFormat.YCbCr_422_SP:
//        return new PlanarYUVLuminanceSource(data, width, height, rect.left,
//                rect.top, rect.width(), rect.height(),false);
//      default:
//        // The Samsung Moment incorrectly uses this variant instead of the
//        // 'sp' version.
//        // Fortunately, it too has all the Y data up front, so we can read
//        // it.
//        if ("yuv420p".equals(previewFormatString)) {
//          return new PlanarYUVLuminanceSource(data, width, height,
//                  rect.left, rect.top, rect.width(), rect.height(),false);
//        }
//    }
//    throw new IllegalArgumentException("Unsupported picture format: "
//            + previewFormat + '/' + previewFormatString);
//  }

    /**
     * 获取通知栏的高度
     */
    public static int getStatusBarHeight(Context mContext) {
        Class<?> c = null;
        Object obj = null;
        Field field = null;
        int x = 0, sbar = 0;
        try {
            c = Class.forName("com.android.internal.R$dimen");
            obj = c.newInstance();
            field = c.getField("status_bar_height");
            x = Integer.parseInt(field.get(obj).toString());
            sbar = mContext.getResources().getDimensionPixelSize(x);
        } catch (Exception e1) {
            e1.printStackTrace();
        }
        return sbar;
    }

    /**
     * 当前摄像头是否支持缩放
     *
     * @return
     */
    public boolean isCurCameraSupportZoom() {
        if (camera != null) {
            Camera theCamera = camera.getCamera();
            if (theCamera != null) {
                Camera.Parameters curParameter = theCamera.getParameters();
                if (curParameter != null) {
                    return curParameter.isZoomSupported();
                }
            }
        }
        return false;
    }

    public int getCameraCurZoom() {
        return configManager != null ? configManager.getCameraCurZoom(getCurCameraParameter()) : 0;
    }

    public int getCameraMaxZoom() {
        return configManager != null ? configManager.getCameraMaxZoom(getCurCameraParameter()) : 100;
    }

    public void zoomCamera(int zoomValue) {
        if (configManager != null) {
            Camera.Parameters curCameraParameter = getCurCameraParameter();
            configManager.setZoom(curCameraParameter, zoomValue);
            if (curCameraParameter != null) {
                getCurOpenedCamera().setParameters(curCameraParameter);
            }
        }
    }

    private Camera.Parameters getCurCameraParameter() {
        Camera curOpenedCamera = getCurOpenedCamera();
        if (curOpenedCamera != null) {
            return curOpenedCamera.getParameters();
        }
        return null;
    }

    private Camera getCurOpenedCamera() {
        if (camera != null) {
            return camera.getCamera();
        }
        return null;
    }

    private float getCurDensity() {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(dm);
        return dm.density;
    }
}
