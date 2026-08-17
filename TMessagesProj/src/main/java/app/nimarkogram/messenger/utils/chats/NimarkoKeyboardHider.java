 
package app.nimarkogram.messenger.utils.chats;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.ChatActivityEnterView;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoKeyboardHider {

    private NimarkoKeyboardHider() {}

    public static void attachTo(
            @NonNull RecyclerView recyclerView,
            @NonNull View contentView,
            @NonNull ChatActivityEnterView chatActivityEnterView
    ) {
        
        final int VELOCITY_THRESHOLD = dp(NimarkoConfig.hideKeyboardOnScrollIntensity * 1000);
        final int invertedSensitivity = dp(10000) - VELOCITY_THRESHOLD + 1;

        recyclerView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            private VelocityTracker velocityTracker = null;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (velocityTracker == null) {
                            velocityTracker = VelocityTracker.obtain();
                        } else {
                            velocityTracker.clear();
                        }
                        velocityTracker.addMovement(e);
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(e);
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(e);
                            velocityTracker.computeCurrentVelocity(1000);
                            float velocityY = velocityTracker.getYVelocity();

                            if (Math.abs(velocityY) > invertedSensitivity
                                    && NimarkoConfig.hideKeyboardOnScrollIntensity > 0) {
                                chatActivityEnterView.hidePopup(true);
                                AndroidUtilities.hideKeyboard(contentView);
                            }

                            velocityTracker.recycle();
                            velocityTracker = null;
                        }
                        break;

                    case MotionEvent.ACTION_CANCEL:
                        if (velocityTracker != null) {
                            velocityTracker.recycle();
                            velocityTracker = null;
                        }
                        break;
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {}

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
        });
    }
}
