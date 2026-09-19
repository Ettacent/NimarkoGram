/*
 * Copyright (C) 2016 The Android Open Source Project
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
package org.telegram.ui;

import android.content.Context;
import android.graphics.Matrix;
import androidx.annotation.IntDef;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;




public class AspectRatioFrameLayout extends FrameLayout {


  public interface AspectRatioListener {










    void onAspectRatioUpdated(
        float targetAspectRatio, float naturalAspectRatio, boolean aspectRatioMismatch);
  }







  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    RESIZE_MODE_FIT,
    RESIZE_MODE_FIXED_WIDTH,
    RESIZE_MODE_FIXED_HEIGHT,
    RESIZE_MODE_FILL,
    RESIZE_MODE_ZOOM
  })
  public @interface ResizeMode {}




  public static final int RESIZE_MODE_FIT = 0;



  public static final int RESIZE_MODE_FIXED_WIDTH = 1;



  public static final int RESIZE_MODE_FIXED_HEIGHT = 2;



  public static final int RESIZE_MODE_FILL = 3;



  public static final int RESIZE_MODE_ZOOM = 4;











  private static final float MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f;

  private final AspectRatioUpdateDispatcher aspectRatioUpdateDispatcher;

  private AspectRatioListener aspectRatioListener;

  private float videoAspectRatio;
  private @ResizeMode int resizeMode;
  private boolean drawingReady;
  private int rotation;
  private Matrix matrix = new Matrix();

  public AspectRatioFrameLayout(Context context) {
    super(context);
    resizeMode = RESIZE_MODE_FIT;
    aspectRatioUpdateDispatcher = new AspectRatioUpdateDispatcher();
  }






  public void setAspectRatio(float widthHeightRatio, int rotation) {
    if (this.videoAspectRatio != widthHeightRatio || this.rotation != rotation) {
      this.videoAspectRatio = widthHeightRatio;
      this.rotation = rotation;
      requestLayout();
    }
  }






  public void setAspectRatioListener(AspectRatioListener listener) {
    this.aspectRatioListener = listener;
  }


  public @ResizeMode int getResizeMode() {
    return resizeMode;
  }






  public void setResizeMode(@ResizeMode int resizeMode) {
    if (this.resizeMode != resizeMode) {
      this.resizeMode = resizeMode;
      requestLayout();
    }
  }

  public void setDrawingReady(boolean value) {
    if (drawingReady == value) {
      return;
    }
    drawingReady = value;
  }

  public float getAspectRatio() {
    return videoAspectRatio;
  }

  public int getVideoRotation() {
    return rotation;
  }

  public boolean isDrawingReady() {
    return drawingReady;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    if (videoAspectRatio <= 0) {

      return;
    }

    int width = getMeasuredWidth();
    int height = getMeasuredHeight();
    float viewAspectRatio = (float) width / height;
    float aspectDeformation = videoAspectRatio / viewAspectRatio - 1;
    if (Math.abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {

      aspectRatioUpdateDispatcher.scheduleUpdate(videoAspectRatio, viewAspectRatio, false);
    } else {
      switch (resizeMode) {
        case RESIZE_MODE_FIXED_WIDTH:
          height = (int) (width / videoAspectRatio);
          break;
        case RESIZE_MODE_FIXED_HEIGHT:
          width = (int) (height * videoAspectRatio);
          break;
        case RESIZE_MODE_ZOOM:
          if (aspectDeformation > 0) {
            width = (int) (height * videoAspectRatio);
          } else {
            height = (int) (width / videoAspectRatio);
          }
          break;
        case RESIZE_MODE_FIT:
          if (aspectDeformation > 0) {
            height = (int) (width / videoAspectRatio);
          } else {
            width = (int) (height * videoAspectRatio);
          }
          break;
        case RESIZE_MODE_FILL:
          if (aspectDeformation <= 0) {
            height = (int) (width / videoAspectRatio);
          } else {
            width = (int) (height * videoAspectRatio);
          }
        default:

          break;
      }
      aspectRatioUpdateDispatcher.scheduleUpdate(videoAspectRatio, viewAspectRatio, true);
      super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    int count = getChildCount();
    for (int a = 0; a < count; a++) {
      View child = getChildAt(a);
      if (child instanceof TextureView) {
        matrix.reset();
        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();
        int px = measuredWidth / 2;
        int py = measuredHeight / 2;
        matrix.postRotate(rotation, px, py);
        if ((rotation == 90 || rotation == 270) && measuredWidth != 0 && measuredHeight != 0) {
          float ratio = (float) measuredHeight / measuredWidth;
          matrix.postScale(1 / ratio, ratio, px, py);
        }
        ((TextureView) child).setTransform(matrix);
        break;
      }
    }
  }


  private final class AspectRatioUpdateDispatcher implements Runnable {

    private float targetAspectRatio;
    private float naturalAspectRatio;
    private boolean aspectRatioMismatch;
    private boolean isScheduled;

    public void scheduleUpdate(
        float targetAspectRatio, float naturalAspectRatio, boolean aspectRatioMismatch) {
      this.targetAspectRatio = targetAspectRatio;
      this.naturalAspectRatio = naturalAspectRatio;
      this.aspectRatioMismatch = aspectRatioMismatch;

      if (!isScheduled) {
        isScheduled = true;
        post(this);
      }
    }

    @Override
    public void run() {
      isScheduled = false;
      if (aspectRatioListener == null) {
        return;
      }
      aspectRatioListener.onAspectRatioUpdated(
          targetAspectRatio, naturalAspectRatio, aspectRatioMismatch);
    }
  }
}
