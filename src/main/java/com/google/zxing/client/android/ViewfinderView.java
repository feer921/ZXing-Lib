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

package com.google.zxing.client.android;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import java.util.Collection;
import java.util.HashSet;

/**
 * This view is overlaid on top of the camera preview. It adds the viewfinder
 * rectangle and partial transparency outside it, as well as the laser scanner
 * animation and result points.
 */
public final class ViewfinderView extends View {

    /**
     * 缩放控件
     */
    private SeekBar mSeekBar;

    public void setSeekBar(SeekBar mSeekBar) {
        this.mSeekBar = mSeekBar;
    }

    /**
     * 刷新界面的时间
     */
    private static final long ANIMATION_DELAY = 10L;
    private static final int OPAQUE = 0xFF;

    /**
     * 画笔对象的引用
     */
    private Paint paint;

    /**
     * 中间滑动线的最顶端位置
     */
    private int animLineSlideTop;
    /**
     * 当前中间动画线条是否向下移动
     */
    private boolean animLineSlideToDown;
    private int mCenterAnimLineH;// 中间横线高度
    private int mCenterLineMoveSpeed;// 中间横线滚动速度
    /**
     * 扫描结果位图
     * @deprecated
     */
    private Bitmap resultBitmap;
//    /**
//     * 扫描结果的颜色
//     * @deprecated
//     */
//    private int scanResultColor;

    /**
     * 环绕取景框的背景颜色
     */
    private int aroundViewfinderBgColor;// 背景颜色
    private int viewfinderOutlineWidth;// 边框的宽度
    /**
     * 四个角(直角)一边的边长
     */
    private int cornerSideLineLength;// 图片宽的长度
    /**
     * 角的每条线/边的线/边宽
     */
    private int cornerSideLineWidth;// 图片的宽度
    /**
     * 取景框轮廓线条的颜色
     */
    private int viewfinderOutlineColor;// 线条框的颜色
    private int cornerColor;// 图片框的颜色
    private boolean isCornerInSide;// 是否是内圈绘制边框

    private Drawable drawableTop;
    private Drawable drawableBottom;
    private Drawable drawableLeft;
    private Drawable drawableRight;
    /**
     * 显示正在扫描的动画线条
     */
    private Drawable scanAnimLine;
    /**
     * 扫描出的有效的像素点的颜色
     */
    private int validScanedPointPixelsColor;
    private Collection<ResultPoint> possibleResultPoints;
    private Collection<ResultPoint> lastPossibleResultPoints;
    /**
     * 是否第一次绘制
     */
    boolean isFirstDraw;

    /**
     * 四个角的显示
     */
    private boolean isLeftRightCornerShow;
    private boolean isTopBottomCornerShow;

    public ViewfinderView(Context context) {
        this(context, null);
    }

    public ViewfinderView(Context context, AttributeSet attrs) {
        this(context, attrs, R.style.viewfinder_style);
    }

    public ViewfinderView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources res = getResources();
        final int defAroundBgColor = res.getColor(R.color.scanner_bg_default);
//        final int defScanedResultBgColor = res.getColor(R.color.scanner_bg_end_default);
        final int defOutlineColor = res.getColor(R.color.scanner_rect_color_default);
        final int defCornerColor = res.getColor(R.color.scanner_image_color_default);
        final int defScanedPointColor = res.getColor(R.color.scanner_point_color_default);
        final boolean defLeftRightCornerShow = res.getBoolean(R.bool.scanner_draw_lr_default);
        final boolean defTopBottomCornerShow = res.getBoolean(R.bool.scanner_draw_tb_default);
        final boolean defaultIsInside = res.getBoolean(R.bool.scanner_inside_default);

        final int defOutlineWidth = res.getDimensionPixelSize(R.dimen.scanner_rect_frame_default);
        final int defCornerRectWidth = res.getDimensionPixelSize(R.dimen.scanner_image_frame_default);
        final int defCornerSideLineLength = res.getDimensionPixelSize(R.dimen.scanner_image_width_default);
        final int defaultLineHeight = res.getDimensionPixelSize(R.dimen.scanner_line_height_default);
        final int defaultLineSpeed = res.getInteger(R.integer.scanner_line_speed_default);

        final int defaultTop = -1;
        final int defaultBottom = -1;
        final int defaultLR = -1;

        // Retrieve styles attributes
        TypedArray a = context.obtainStyledAttributes(attrs,R.styleable.Scanner, defStyle, 0);
        aroundViewfinderBgColor = a.getColor(R.styleable.Scanner_around_bg_color, defAroundBgColor);
//        scanResultColor = a.getColor(R.styleable.Scanner_bg_end, defScanedResultBgColor);
        viewfinderOutlineColor = a.getColor(R.styleable.Scanner_viewfinder_outline_color,defOutlineColor);
        cornerColor = a.getColor(R.styleable.Scanner_corner_color,defCornerColor);
        validScanedPointPixelsColor = a.getColor(R.styleable.Scanner_scaned_point_color,defScanedPointColor);
        viewfinderOutlineWidth = a.getDimensionPixelSize(R.styleable.Scanner_viewfinder_outline_width,defOutlineWidth);

        cornerSideLineWidth = a.getDimensionPixelSize(R.styleable.Scanner_corner_side_line_width,defCornerRectWidth);
        cornerSideLineLength = a.getDimensionPixelSize(R.styleable.Scanner_corner_side_line_length,defCornerSideLineLength);

        mCenterAnimLineH = a.getDimensionPixelSize(R.styleable.Scanner_center_anim_line_h,defaultLineHeight);
        mCenterLineMoveSpeed = a.getInt(R.styleable.Scanner_center_line_move_speed, defaultLineSpeed);

        isLeftRightCornerShow = a.getBoolean(R.styleable.Scanner_draw_left_right_corner, defLeftRightCornerShow);
        isTopBottomCornerShow = a.getBoolean(R.styleable.Scanner_draw_top_bottom_corner, defTopBottomCornerShow);
        isCornerInSide = a.getBoolean(R.styleable.Scanner_isCornerInside, defaultIsInside);

        if (isTopBottomCornerShow) {
            int topId = a.getResourceId(R.styleable.Scanner_around_top_bg_res, defaultTop);
            if (topId > 0) {
                drawableTop = res.getDrawable(topId);
            }
            int bottomId = a.getResourceId(R.styleable.Scanner_around_bottom_res,defaultBottom);
            if (bottomId > 0) {
                drawableBottom = res.getDrawable(bottomId);
            }
        }
        if (isLeftRightCornerShow) {
            int lrID = a.getResourceId(R.styleable.Scanner_around_lr_bg_res, defaultLR);
            if (lrID > 0) {
                drawableLeft = res.getDrawable(lrID);
                drawableRight = res.getDrawable(lrID);
            }

        }
        int centerLineDrawableId = a.getResourceId(R.styleable.Scanner_center_anim_line, defaultLR);
        if (centerLineDrawableId > 0) {
            scanAnimLine = res.getDrawable(centerLineDrawableId);
        }
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        possibleResultPoints = new HashSet<ResultPoint>(5);
        lastPossibleResultPoints = null;
        a.recycle();
    }

    CameraManager cameraManager;

    public void configCameraManager(CameraManager cameraManager) {
        this.cameraManager = cameraManager;
    }

    /**
     * 取景框的矩形范围
     */
    private Rect viewfinderRect;
    @Override
    public void onDraw(Canvas canvas) {
        if (cameraManager == null) {
            return;
        }
//         中间的扫描框，你要修改扫描框的大小，去CameraManager里面修改
//        Rect viewfinderRect = cameraManager.getFramingRect();
//        if (viewfinderRect == null) {
//            return;
//        }
        if (this.viewfinderRect == null) {
            this.viewfinderRect = cameraManager.getFramingRect();
        }
        if (this.viewfinderRect == null) {//不绘制了
            return;
        }
        // 初始化中间线滑动的最上边和最下边
        if (!isFirstDraw) {
            isFirstDraw = true;
            animLineSlideTop = viewfinderRect.top;
        }

        // 获取屏幕的宽和高
        int width = canvas.getWidth();
        int height = canvas.getHeight();

//        paint.setColor(resultBitmap != null ? scanResultColor : aroundViewfinderBgColor);
        paint.setColor(aroundViewfinderBgColor);

        // 画出扫描框外面的阴影部分，共四个部分，扫描框的上面到屏幕上面，扫描框的下面到屏幕下面
        // 扫描框的左边面到屏幕左边，扫描框的右边到屏幕右边
        canvas.drawRect(0, 0, width, viewfinderRect.top, paint);
        canvas.drawRect(0, viewfinderRect.top, viewfinderRect.left, viewfinderRect.bottom + viewfinderOutlineWidth, paint);
        canvas.drawRect(viewfinderRect.right + viewfinderOutlineWidth, viewfinderRect.top, width, viewfinderRect.bottom + viewfinderOutlineWidth, paint);
        canvas.drawRect(0, viewfinderRect.bottom + viewfinderOutlineWidth, width, height, paint);

        if (resultBitmap != null) {
            // Draw the opaque result bitmap over the scanning rectangle
            paint.setAlpha(OPAQUE);
            canvas.drawBitmap(resultBitmap, viewfinderRect.left, viewfinderRect.top, paint);
        }
        else {
            // 画扫描框边上的角，总共4个角每个角2小短线即8个部分
            paint.setColor(cornerColor);
            if (drawableTop == null || drawableBottom == null) {
                int mSpelce = viewfinderOutlineWidth + cornerSideLineWidth;
                int left = viewfinderRect.left - mSpelce;
                int right = viewfinderRect.right + mSpelce;
                int top = viewfinderRect.top - mSpelce;
                int bottom = viewfinderRect.bottom + mSpelce;

                if (isCornerInSide) {
                    mSpelce = viewfinderOutlineWidth;
                    left = viewfinderRect.left + mSpelce;
                    right = viewfinderRect.right - mSpelce;
                    top = viewfinderRect.top + mSpelce;
                    bottom = viewfinderRect.bottom - mSpelce;
                }

                if (isLeftRightCornerShow) {
                    // 左上角横线
                    canvas.drawRect(left, top, left + cornerSideLineLength, top + cornerSideLineWidth, paint);
                    // 左上角竖线
                    canvas.drawRect(left, top, left + cornerSideLineWidth, top + cornerSideLineLength, paint);
                    // 右上角横线
                    canvas.drawRect(right - cornerSideLineLength, top, right, top + cornerSideLineWidth, paint);
                    // 右上角竖线
                    canvas.drawRect(right - cornerSideLineWidth, top, right, top + cornerSideLineLength, paint);
                }

                if (isTopBottomCornerShow) {
                    // 左下角横线
                    canvas.drawRect(left, bottom - cornerSideLineWidth, left + cornerSideLineLength, bottom, paint);
                    // 左下角竖线
                    canvas.drawRect(left, bottom - cornerSideLineLength, left + cornerSideLineWidth, bottom, paint);
                    // 右下角横线
                    canvas.drawRect(right - cornerSideLineLength, bottom - cornerSideLineWidth, right, bottom, paint);
                    // 右下角竖线
                    canvas.drawRect(right - cornerSideLineWidth, bottom - cornerSideLineLength, right, bottom, paint);
                }
            }

            paint.setColor(viewfinderOutlineColor);
            // 绘制上面长方形
            canvas.drawRect(viewfinderRect.left - viewfinderOutlineWidth, viewfinderRect.top - viewfinderOutlineWidth,
                    viewfinderRect.right + viewfinderOutlineWidth, viewfinderRect.top + viewfinderOutlineWidth, paint);
            // 绘制左边长方形
            canvas.drawRect(viewfinderRect.left - viewfinderOutlineWidth, viewfinderRect.top - viewfinderOutlineWidth,
                    viewfinderRect.left + viewfinderOutlineWidth, viewfinderRect.bottom + viewfinderOutlineWidth, paint);
            // 绘制右边长方形
            canvas.drawRect(viewfinderRect.right - viewfinderOutlineWidth, viewfinderRect.top - viewfinderOutlineWidth,
                    viewfinderRect.right + viewfinderOutlineWidth, viewfinderRect.bottom + viewfinderOutlineWidth, paint);
            // 绘制下面长方形
            canvas.drawRect(viewfinderRect.left - viewfinderOutlineWidth, viewfinderRect.bottom - viewfinderOutlineWidth,
                    viewfinderRect.right + viewfinderOutlineWidth, viewfinderRect.bottom + viewfinderOutlineWidth, paint);
            int drawSize = cornerSideLineWidth + viewfinderOutlineWidth - 1;
            if (drawableTop != null) {
                drawableTop.setBounds(viewfinderRect.left - drawSize, viewfinderRect.top
                        - drawSize, viewfinderRect.right + drawSize, viewfinderRect.top
                        + drawableTop.getIntrinsicHeight() - drawSize);
            }
            if (drawableBottom != null)
                drawableBottom.setBounds(viewfinderRect.left - drawSize, viewfinderRect.bottom
                                - drawableBottom.getIntrinsicHeight() + drawSize,
                        viewfinderRect.right + drawSize, viewfinderRect.bottom + drawSize);
            if (drawableLeft != null)
                drawableLeft.setBounds(viewfinderRect.left - drawSize, viewfinderRect.top,
                        viewfinderRect.left, viewfinderRect.bottom);
            if (drawableRight != null)
                drawableRight.setBounds(viewfinderRect.right, viewfinderRect.top, viewfinderRect.right
                        + cornerSideLineWidth, viewfinderRect.bottom);
            if (drawableTop != null)
                drawableTop.draw(canvas);
            if (drawableBottom != null)
                drawableBottom.draw(canvas);
            if (drawableLeft != null)
                drawableLeft.draw(canvas);
            if (drawableRight != null)
                drawableRight.draw(canvas);

            //绘制中间的扫描动画线条
            int viewfinderRectTop = viewfinderRect.top;
            int viewfinderRectBottom = viewfinderRect.bottom - 10;
            if (animLineSlideTop <= viewfinderRectTop) {//在顶端了，则往下走
                animLineSlideToDown = true;
            }
            if (animLineSlideTop >= viewfinderRectBottom) {//到底端了,则往上走
                animLineSlideToDown = false;
            }
            if (animLineSlideToDown) {
                animLineSlideTop += mCenterLineMoveSpeed;
            }
            else{
                animLineSlideTop -= mCenterLineMoveSpeed;
            }
            if (scanAnimLine != null) {
                Rect lineRect = new Rect();
                lineRect.left = viewfinderRect.left;
                lineRect.right = viewfinderRect.right;
                lineRect.top = animLineSlideTop;
                lineRect.bottom = animLineSlideTop + mCenterAnimLineH;// 扫描线的宽度15
                canvas.drawBitmap(((BitmapDrawable) (scanAnimLine)).getBitmap(),null,lineRect, paint);
            }
            //绘制有效数据点
            Collection<ResultPoint> currentPossible = possibleResultPoints;
            Collection<ResultPoint> currentLast = lastPossibleResultPoints;
            if (currentPossible.isEmpty()) {
                lastPossibleResultPoints = null;
            }
            else {
                possibleResultPoints = new HashSet<ResultPoint>(5);
                lastPossibleResultPoints = currentPossible;
                paint.setAlpha(OPAQUE);
                paint.setColor(validScanedPointPixelsColor);
                for (ResultPoint point : currentPossible) {
                    canvas.drawCircle(viewfinderRect.left + point.getX(), viewfinderRect.top + point.getY(), 6.0f, paint);
                }
            }
            if (currentLast != null) {
                paint.setAlpha(OPAQUE / 2);
                paint.setColor(validScanedPointPixelsColor);
                for (ResultPoint point : currentLast) {
                    canvas.drawCircle(viewfinderRect.left + point.getX(), viewfinderRect.top + point.getY(), 3.0f, paint);
                }
            }
            // 只刷新扫描框的内容，其他地方不刷新
            postInvalidateDelayed(ANIMATION_DELAY, viewfinderRect.left, viewfinderRect.top,viewfinderRect.right, viewfinderRect.bottom);
        }
    }

    public void drawViewfinder() {
        resultBitmap = null;
        invalidate();
    }

    /**
     * Draw a bitmap with the result points highlighted instead of the live
     * scanning display.
     *
     * @param barcode An image of the decoded barcode.
     */
    public void drawResultBitmap(Bitmap barcode) {
        resultBitmap = barcode;
        invalidate();
    }

    public void addPossibleResultPoint(ResultPoint point) {
        possibleResultPoints.add(point);
    }

    private int mode = 0;// 用于标记模式
    private static final int DRAG = 1;// 拖动
    private static final int ZOOM = 2;// 放大
    private float startDis = 0;
    private int mProgress = 0;

    public boolean onTouchEvent(MotionEvent event) {
        if (mSeekBar == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                mode = DRAG;
                break;

            case MotionEvent.ACTION_MOVE:// 移动事件
                if (mode == ZOOM) {// 图片放大事件
                    float endDis = distance(event);// 结束距离
                    if (endDis > 10f) {
                        float scale = endDis / startDis;// 放大倍数
                        scaleSeekBar(scale);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                mode = 0;
                break;
            case MotionEvent.ACTION_POINTER_UP:// 有手指离开屏幕，但屏幕还有触点(手指)
                mode = 0;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:// 当屏幕上已经有触点（手指）,再有一个手指压下屏幕
                mode = ZOOM;
                startDis = distance(event);
                if (mSeekBar != null) {
                    mProgress = mSeekBar.getProgress();
                }
                break;
        }
        return true;
    }

    private void scaleSeekBar(float twoFingerScaleRatios) {
        if (mSeekBar == null) {
            return;
        }
        int seekbarMaxProgress = mSeekBar.getMax();
        int startSeekbarProgress = mProgress;//第二个手指按下时mSeekBar当前的进度值
        if (startSeekbarProgress == 0) {
            startSeekbarProgress = 2;
        }
        int newProgress = (int) (startSeekbarProgress * twoFingerScaleRatios);
        Log.i("info", "--> scaleSeekBar() scale size:" + twoFingerScaleRatios +
                " maxProgress:" + seekbarMaxProgress + " startProgress:" + startSeekbarProgress +
                " newProgress = " + newProgress);
        if (newProgress > seekbarMaxProgress) {
            newProgress = seekbarMaxProgress;
        }
        if (newProgress >= 0 && newProgress <= seekbarMaxProgress) {
            mSeekBar.setProgress(newProgress);
        }
    }

    /**
     * 两点之间的距离
     *
     * @param event
     * @return
     */
    private static float distance(MotionEvent event) {
        // 两根线的距离
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
