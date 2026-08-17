package org.telegram.messenger.camera;

import android.hardware.Camera;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

import java.util.concurrent.CountDownLatch;

public class CameraSessionWrapper {
    public CameraSession camera1Session;
    public Camera2Session camera2Session;
    public app.nimarkogram.messenger.camera.NimarkoCameraXSurfaceSession cameraXSession;

    public boolean isInitiated() {
        if (cameraXSession != null) {
            return cameraXSession.isInitiated();
        } else if (camera2Session != null) {
            return camera2Session.isInitiated();
        } else if (camera1Session != null) {
            return camera1Session.isInitied();
        } else {
            return false;
        }
    }

    public int getWorldAngle() {
        if (cameraXSession != null) {
            return cameraXSession.getWorldAngle();
        } else if (camera2Session != null) {
            return camera2Session.getWorldAngle();
        } else if (camera1Session != null) {
            return camera1Session.getWorldAngle();
        } else {
            return 0;
        }
    }

    public int getCurrentOrientation() {
        if (cameraXSession != null) {
            return cameraXSession.getCurrentOrientation();
        } else if (camera2Session != null) {
            return camera2Session.getCurrentOrientation();
        } else if (camera1Session != null) {
            return camera1Session.getCurrentOrientation();
        } else {
            return 0;
        }
    }

    public int getDisplayOrientation() {
        if (cameraXSession != null) {
            return cameraXSession.getDisplayOrientation();
        } else if (camera2Session != null) {
            return camera2Session.getDisplayOrientation();
        } else if (camera1Session != null) {
            return camera1Session.getDisplayOrientation();
        } else {
            return 0;
        }
    }

    public boolean isMirrored() {
        return cameraXSession != null && cameraXSession.isMirrored();
    }

    public boolean hasCameraTransform() {
        return cameraXSession != null && cameraXSession.hasCameraTransform();
    }

    @Deprecated
    public int getCameraId() {
        if (cameraXSession != null) {
            return cameraXSession.getCameraId();
        } else if (camera2Session != null) {
            return camera2Session.cameraId.hashCode();
        } else if (camera1Session != null) {
            return camera1Session.cameraInfo.cameraId;
        } else {
            return 0;
        }
    }

    public void stopVideoRecording() {
        if (cameraXSession != null) {
            cameraXSession.enableTorch(false);
        } else if (camera2Session != null) {
            camera2Session.setRecordingVideo(false);
        } else if (camera1Session != null) {
            camera1Session.stopVideoRecording();
        }
    }

    public void setOptimizeForBarcode(boolean optimize) {
        if (cameraXSession != null) {
            return;
        } else if (camera2Session != null) {
            camera2Session.setScanningBarcode(optimize);
        } else if (camera1Session != null) {
            camera1Session.setOptimizeForBarcode(optimize);
        }
    }

    public void setCurrentFlashMode(String flashMode) {
        if (cameraXSession != null) {
            cameraXSession.setCurrentFlashMode(flashMode);
        } else if (camera2Session != null) {
            
        } else if (camera1Session != null) {
            camera1Session.setCurrentFlashMode(flashMode);
        }
    }

    public String getCurrentFlashMode() {
        if (cameraXSession != null) {
            return cameraXSession.getCurrentFlashMode();
        } else if (camera2Session != null) {
            
            return Camera.Parameters.FLASH_MODE_OFF;
        } else if (camera1Session != null) {
            return camera1Session.getCurrentFlashMode();
        }
        return null;
    }

    public String getNextFlashMode() {
        if (cameraXSession != null) {
            return cameraXSession.getNextFlashMode();
        } else if (camera2Session != null) {
            
            return Camera.Parameters.FLASH_MODE_OFF;
        } else if (camera1Session != null) {
            return camera1Session.getNextFlashMode();
        }
        return null;
    }

    public boolean hasFlashModes() {
        if (cameraXSession != null) {
            return cameraXSession.hasFlash();
        } else if (camera2Session != null) {
            
            return false;
        } else if (camera1Session != null) {
            return !camera1Session.availableFlashModes.isEmpty();
        }
        return false;
    }

    public void setFlipFront(boolean flip) {
        if (cameraXSession != null) {
            return;
        } else if (camera2Session != null) {
            
        } else if (camera1Session != null) {
            camera1Session.setFlipFront(flip);
        }
    }

    public boolean isSameTakePictureOrientation() {
        if (cameraXSession != null) {
            return true;
        } else if (camera2Session != null) {
            
        } else if (camera1Session != null) {
            return camera1Session.isSameTakePictureOrientation();
        }
        return true;
    }

    public void updateRotation() {
        if (cameraXSession != null) {
            cameraXSession.updateRotation();
        } else if (camera2Session != null) {
            
        } else if (camera1Session != null) {
            camera1Session.updateRotation();
        }
    }

    public void setZoom(float zoom) {
        if (cameraXSession != null) {
            cameraXSession.setZoom(zoom);
        } else if (camera2Session != null) {
            camera2Session.setZoom(AndroidUtilities.lerp(camera2Session.getMinZoom(), camera2Session.getMaxZoom(), zoom));
        } else if (camera1Session != null) {
            camera1Session.setZoom(zoom);
        }
    }

    public void focusToRect(android.graphics.Rect focusRect, android.graphics.Rect meteringRect) {
        if (cameraXSession != null) {
            cameraXSession.focusToRect(focusRect);
        } else if (camera2Session != null) {
            
        } else if (camera1Session != null) {
            camera1Session.focusToRect(focusRect, meteringRect);
        }
    }

    public void destroy(boolean async, Runnable before, Runnable after) {
        if (cameraXSession != null) {
            if (before != null) before.run();
            cameraXSession.close(after);
        } else if (camera2Session != null) {
            if (before != null) {
                before.run();
            }
            camera2Session.destroy(async, after);
        } else if (camera1Session != null) {
            CameraController.getInstance().close(camera1Session, !async ? new CountDownLatch(1) : null, before, after);
        }
    }

    public Object getObject() {
        if (cameraXSession != null) {
            return cameraXSession;
        } else if (camera2Session != null) {
            return camera2Session;
        } else if (camera1Session != null) {
            return camera1Session;
        }
        return null;
    }

    public static CameraSessionWrapper of(CameraSession session) {
        CameraSessionWrapper wrapper = new CameraSessionWrapper();
        wrapper.camera1Session = session;
        return wrapper;
    }

    public static CameraSessionWrapper of(Camera2Session session) {
        CameraSessionWrapper wrapper = new CameraSessionWrapper();
        wrapper.camera2Session = session;
        return wrapper;
    }

    public static CameraSessionWrapper of(app.nimarkogram.messenger.camera.NimarkoCameraXSurfaceSession session) {
        CameraSessionWrapper wrapper = new CameraSessionWrapper();
        wrapper.cameraXSession = session;
        return wrapper;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof CameraSession) {
            return obj == camera1Session;
        } else if (obj instanceof Camera2Session) {
            return obj == camera2Session;
        } else if (obj instanceof app.nimarkogram.messenger.camera.NimarkoCameraXSurfaceSession) {
            return obj == cameraXSession;
        } else if (obj instanceof CameraSessionWrapper) {
            CameraSessionWrapper wrapper = (CameraSessionWrapper) obj;
            return wrapper == this || wrapper.camera1Session == camera1Session
                    && wrapper.camera2Session == camera2Session && wrapper.cameraXSession == cameraXSession;
        }
        return false;
    }
}
