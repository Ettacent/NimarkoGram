package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class DrawingInBackgroundThreadDrawable implements NotificationCenter.NotificationCenterDelegate {

    public final static int THREAD_COUNT = 2;
    protected static final class ImageReceiverDrawFrame {
        private final ArrayList<ImageReceiver> receivers = new ArrayList<>();
        private final ArrayList<ImageReceiver.BackgroundThreadDrawHolder> holders = new ArrayList<>();
        public ImageReceiver.BackgroundThreadDrawHolder add(ImageReceiver receiver, int threadIndex) {
            final int index = receivers.size();
            final ImageReceiver.BackgroundThreadDrawHolder holder = receiver.setDrawInBackgroundThread(
                    index < holders.size() ? holders.get(index) : null, threadIndex);
            if (index == holders.size()) holders.add(holder);
            else holders.set(index, holder);
            receivers.add(receiver);
            return holder;
        }
        public void draw(Canvas canvas) {
            for (int i = 0; i < receivers.size(); i++) {
                receivers.get(i).draw(canvas, holders.get(i));
            }
        }
        public void release() {
            for (int i = 0; i < receivers.size(); i++) holders.get(i).release();
            receivers.clear();
        }
    }
    boolean attachedToWindow;

    Bitmap backgroundBitmap;
    Canvas backgroundCanvas;

    Bitmap bitmap;
    Canvas bitmapCanvas;

    private boolean bitmapUpdating;

    private int currentLayerNum = 1;
    private int currentOpenedLayerFlags;
    protected boolean paused;

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    int frameGuid;

    int height;
    int width;
    int padding;

    public static DispatchQueuePool queuePool;
    private final DispatchQueue backgroundQueue;
    boolean error;
    private boolean backgroundFrameError;

    private final Runnable bitmapCreateTask = new Runnable() {
        @Override
        public void run() {
            backgroundFrameError = false;
            try {
                int heightInternal = height + padding;
                if (backgroundBitmap == null || backgroundBitmap.getWidth() != width || backgroundBitmap.getHeight() != heightInternal) {
                    if (backgroundBitmap != null) {
                        backgroundBitmap.recycle();
                    }
                    backgroundBitmap = Bitmap.createBitmap(width, heightInternal, Bitmap.Config.ARGB_8888);

                    backgroundCanvas = new Canvas(backgroundBitmap);
                }

                backgroundBitmap.eraseColor(Color.TRANSPARENT);
                int saveCount = backgroundCanvas.save();
                try {
                    backgroundCanvas.translate(0, padding);
                    drawInBackground(backgroundCanvas);
                } finally {
                    backgroundCanvas.restoreToCount(saveCount);
                }
                backgroundBitmap.prepareToDraw();
            } catch (Exception e) {
                FileLog.e(e);
                backgroundFrameError = true;
            }

            AndroidUtilities.runOnUIThread(uiFrameRunnable);
        }
    };

    boolean needSwapBitmaps;

    Runnable uiFrameRunnable = new Runnable() {
        @Override
        public void run() {
            bitmapUpdating = false;
            onFrameReady();
            if (!attachedToWindow) {
                recycleBitmaps();
                return;
            }
            if (frameGuid != lastFrameId) {
                return;
            }
            error = backgroundFrameError;
            if (error) {
                return;
            }
            needSwapBitmaps = true;
        }
    };
    private boolean reset;
    private int lastFrameId;
    public final int threadIndex;

    public DrawingInBackgroundThreadDrawable() {
        if (queuePool == null) {
            queuePool = new DispatchQueuePool(THREAD_COUNT);
        }
        backgroundQueue = queuePool.getNextQueue();
        threadIndex = queuePool.pointer;
    }

    public void draw(Canvas canvas, long time, int w, int h, float alpha) {
        if (error) {
            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                canvas.drawRect(0, 0, w, h, Theme.DEBUG_RED);
            }
            return;
        }
        height = h;
        width = w;

        if (needSwapBitmaps) {
            needSwapBitmaps = false;
            Bitmap bitmapTmp = bitmap;
            Canvas bitmapCanvasTmp = bitmapCanvas;

            bitmap = backgroundBitmap;
            bitmapCanvas = backgroundCanvas;

            backgroundBitmap = bitmapTmp;
            backgroundCanvas = bitmapCanvasTmp;
        }

        if (bitmap == null || reset) {
            reset = false;

            if (bitmap != null) {
                ArrayList<Bitmap> bitmaps = new ArrayList<>();
                bitmaps.add(bitmap);
                AndroidUtilities.recycleBitmaps(bitmaps);
                bitmap = null;
            }
            int heightInternal = height + padding;
            if (bitmap == null || bitmap.getHeight() != heightInternal || bitmap.getWidth() != width) {
                bitmap = Bitmap.createBitmap(width, heightInternal, Bitmap.Config.ARGB_8888);
                bitmapCanvas = new Canvas(bitmap);
            } else {
                bitmap.eraseColor(Color.TRANSPARENT);
            }
            bitmapCanvas.save();
            bitmapCanvas.translate(0, padding);
            drawInUiThread(bitmapCanvas, 1f);
            bitmapCanvas.restore();
        }

        if (!bitmapUpdating && !paused) {
            bitmapUpdating = true;
            prepareDraw(time);
            lastFrameId = frameGuid;
            backgroundQueue.postRunnable(bitmapCreateTask);
        }

        if (bitmap != null ) {
            Bitmap drawingBitmap = bitmap;
            paint.setAlpha((int) (0xFF * alpha));
            canvas.save();
            canvas.translate(0, -padding);
            this.drawBitmap(canvas, drawingBitmap, paint);
            canvas.restore();
        }
    }

    protected void drawBitmap(Canvas canvas, Bitmap bitmap, Paint paint) {
        canvas.drawBitmap(bitmap, 0, 0, paint);
    }

    protected void drawInUiThread(Canvas nextRenderingCanvas, float alpha) {

    }

    public void drawInBackground(Canvas canvas) {

    }

    public void prepareDraw(long time) {

    }

    public void onFrameReady() {

    }

    public void onAttachToWindow() {
        if (attachedToWindow) {
            return;
        }
        attachedToWindow = true;
        error = false;
        updateHeavyOperationState();

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.stopAllHeavyOperations);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.startAllHeavyOperations);
    }

    public void onDetachFromWindow() {
        if (!attachedToWindow) {
            return;
        }
        attachedToWindow = false;
        frameGuid++;
        needSwapBitmaps = false;
        reset = true;
        if (!bitmapUpdating) {
            recycleBitmaps();
        }
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.stopAllHeavyOperations);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.startAllHeavyOperations);
    }

    private void recycleBitmaps() {
        needSwapBitmaps = false;
        ArrayList<Bitmap> bitmaps = new ArrayList<>();
        if (bitmap != null) {
            bitmaps.add(bitmap);
        }
        if (backgroundBitmap != null) {
            bitmaps.add(backgroundBitmap);
        }
        bitmap = null;
        backgroundBitmap = null;
        backgroundCanvas = null;
        bitmapCanvas = null;
        AndroidUtilities.recycleBitmaps(bitmaps);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.stopAllHeavyOperations) {
            Integer layer = (Integer) args[0];
            if (currentLayerNum >= layer || (layer == 512 && (SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH))) {
                return;
            }
            currentOpenedLayerFlags |= layer;
            updatePausedState();
        } else if (id == NotificationCenter.startAllHeavyOperations) {
            Integer layer = (Integer) args[0];
            if (currentLayerNum >= layer || currentOpenedLayerFlags == 0) {
                return;
            }
            currentOpenedLayerFlags &= ~layer;
            updatePausedState();
        }
    }
    private void updateHeavyOperationState() {
        currentOpenedLayerFlags = NotificationCenter.getGlobalInstance().getCurrentHeavyOperationFlags();
        currentOpenedLayerFlags &= ~currentLayerNum;
        if (SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH) {
            currentOpenedLayerFlags &= ~512;
        }
        updatePausedState();
    }
    private void updatePausedState() {
        boolean shouldPause = currentOpenedLayerFlags != 0;
        if (paused == shouldPause) {
            return;
        }
        paused = shouldPause;
        if (paused) {
            onPaused();
        } else {
            onResume();
        }
    }

    public void onResume() {

    }

    public void onPaused() {

    }

    public void reset() {
        reset = true;
        frameGuid++;
        needSwapBitmaps = false;

        if (bitmap != null) {
            ArrayList<Bitmap> bitmaps = new ArrayList<>();
            bitmaps.add(bitmap);
            bitmap = null;
            AndroidUtilities.recycleBitmaps(bitmaps);
        }
    }

    public static class DispatchQueuePool {
        final int size;
        int pointer;

        public final DispatchQueue[] pool;

        private DispatchQueuePool(int size) {
            this.size = size;
            pool = new DispatchQueue[size];
        }

        public DispatchQueue getNextQueue() {
            pointer++;
            if (pointer > size - 1) {
                pointer = 0;
            }
            DispatchQueue queue = pool[pointer];
            if (queue == null) {
                queue = pool[pointer] = new DispatchQueue("draw_background_queue_" + pointer);
            }
            return queue;
        }
    }

    public void setLayerNum(int value) {
        currentLayerNum = value;
        if (attachedToWindow) {
            updateHeavyOperationState();
        }
    }
}
