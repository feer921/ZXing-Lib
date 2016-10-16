package com.google.zxing.client.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;

import com.google.zxing.Result;
import com.google.zxing.client.android.camera.CameraManager;

/**
 * User: fee(1176610771@qq.com)
 * Date: 2016-10-11
 * Time: 10:54
 * DESC: 拥有摄像头扫描功能的Activity接口
 */
public interface IScanActivity {
    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     *
     * @param rawResult The contents of the barcode.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param barcode   A greyscale bitmap of the camera data which was decoded.
     */
    void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor);

    /**
     * 让当前Activity绘制取景框
     */
    void drawViewfinder();

    Handler getHandler();
    CameraManager getCameraManager();

    /**
     * 获取当前摄像头扫描Activity中的取景框控件
     * @return
     */
    ViewfinderView getViewfinderView();

    Activity getActivity();
}
