/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.voip;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;

@SuppressLint("ViewConstructor")
public class VoIpSwitchLayout extends FrameLayout {

    public enum Type {
        MICRO,
        CAMERA,
        VIDEO,
        BLUETOOTH,
        SPEAKER,
    }

    private final VoIPBackgroundProvider backgroundProvider;
    private VoIpButtonView voIpButtonView;
    private Type type;
    private final TextView currentTextView;
    private final TextView newTextView;
    public int animationDelay;

    public void setOnBtnClickedListener(VoIpButtonView.OnBtnClickedListener onBtnClickedListener) {
        voIpButtonView.setOnBtnClickedListener(onBtnClickedListener);
    }

    public VoIpSwitchLayout(@NonNull Context context, VoIPBackgroundProvider backgroundProvider) {
        super(context);
        this.backgroundProvider = backgroundProvider;
        setWillNotDraw(true);
        voIpButtonView = new VoIpButtonView(context, backgroundProvider);
        addView(voIpButtonView, LayoutHelper.createFrame(VoIpButtonView.ITEM_SIZE + 1.5f, VoIpButtonView.ITEM_SIZE + 1.5f, Gravity.CENTER_HORIZONTAL));

        currentTextView = new TextView(context);
        currentTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        currentTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        currentTextView.setTextColor(Color.WHITE);
        currentTextView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(currentTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, VoIpButtonView.ITEM_SIZE + 6, 0, 2));

        newTextView = new TextView(context);
        newTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        newTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        newTextView.setTextColor(Color.WHITE);
        newTextView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(newTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, VoIpButtonView.ITEM_SIZE + 6, 0, 2));
        currentTextView.setVisibility(GONE);
        newTextView.setVisibility(GONE);
    }

    private void setText(Type type, boolean isSelectedState) {
        final String newText;
        switch (type) {
            case MICRO:
                if (isSelectedState) {
                    newText = LocaleController.getString(R.string.VoipUnmute);
                } else {
                    newText = LocaleController.getString(R.string.VoipMute);
                }
                break;
            case CAMERA:
                newText = LocaleController.getString(R.string.VoipFlip);
                break;
            case VIDEO:
                if (isSelectedState) {
                    newText = LocaleController.getString(R.string.VoipStartVideo);
                } else {
                    newText = LocaleController.getString(R.string.VoipStopVideo);
                }
                break;
            case BLUETOOTH:
                newText = LocaleController.getString(R.string.VoipAudioRoutingBluetooth);
                break;
            case SPEAKER:
                newText = LocaleController.getString(R.string.VoipSpeaker);
                break;
            default:
                newText = "";
        }
        setContentDescription(newText);

        if (currentTextView.getVisibility() == GONE && newTextView.getVisibility() == GONE) {
            currentTextView.setVisibility(VISIBLE);
            currentTextView.setText(newText);
            newTextView.setText(newText);
            return;
        }

        if (newTextView.getText().equals(newText) && currentTextView.getText().equals(newText)) {
            return;
        }

        currentTextView.animate().alpha(0f).translationY(-AndroidUtilities.dp(4)).setDuration(140).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                currentTextView.setText(newText);
                currentTextView.setTranslationY(0);
                currentTextView.setAlpha(1.0f);
            }
        }).start();
        newTextView.setText(newText);
        newTextView.setVisibility(VISIBLE);
        newTextView.setAlpha(0);
        newTextView.setTranslationY(AndroidUtilities.dp(5));
        newTextView.animate().alpha(1.0f).translationY(0).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                newTextView.setVisibility(GONE);
            }
        }).start();
    }

    private void attachNewButton(int rawRes, int size, boolean isSelected, Type type) {
        final VoIpButtonView newVoIpButtonView = new VoIpButtonView(getContext(), backgroundProvider);
        newVoIpButtonView.setIcons(rawRes, size);
        newVoIpButtonView.setSelectedState(isSelected, false, type);
        newVoIpButtonView.setAlpha(0f);
        newVoIpButtonView.setOnBtnClickedListener(voIpButtonView.onBtnClickedListener);
        addView(newVoIpButtonView, LayoutHelper.createFrame(VoIpButtonView.ITEM_SIZE + 1.5f, VoIpButtonView.ITEM_SIZE + 1.5f, Gravity.CENTER_HORIZONTAL));
        final VoIpButtonView oldVoIpButton = voIpButtonView;
        oldVoIpButton.setOnBtnClickedListener(null);
        voIpButtonView = newVoIpButtonView;
        newVoIpButtonView.animate().alpha(1f).setDuration(250).start();
        oldVoIpButton.animate().alpha(0f).setDuration(250).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeView(oldVoIpButton);
            }
        }).start();
    }

    public void setType(Type newType, boolean isSelected) {
        setType(newType, isSelected, false);
    }

    public void setType(Type newType, boolean isSelected, boolean fast) {
        if (this.type == newType && isSelected == voIpButtonView.isSelectedState) {
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            return;
        }
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
        }
        int size = AndroidUtilities.dp(VoIpButtonView.ITEM_SIZE + 1.5f);
        boolean ignoreSetState = false;
        switch (newType) {
            case MICRO:
                if (this.type != Type.MICRO) {
                    voIpButtonView.setIcons(R.raw.call_mute, size);
                }
                break;
            case VIDEO:
                //R.drawable.calls_sharescreen screencast is not used in the design
                if (this.type != Type.VIDEO) {
                    voIpButtonView.setIcons(R.raw.video_stop, size);
                }
                break;
            case CAMERA:
                if (this.type == Type.SPEAKER || this.type == Type.BLUETOOTH) {
                    ignoreSetState = true;
                    attachNewButton(R.raw.camera_flip2, size, isSelected, newType);
                } else if (this.type != Type.CAMERA) {
                    voIpButtonView.setIcons(R.raw.camera_flip2, size);
                }
                break;
            case SPEAKER:
                if (this.type == Type.BLUETOOTH) {
                    ignoreSetState = animateRouteIcon(newType, isSelected, R.raw.speaker_to_bt, size);
                } else if (this.type == Type.CAMERA) {
                    ignoreSetState = true;
                    attachNewButton(R.raw.speaker_to_bt, size, isSelected, newType);
                } else if (this.type != Type.SPEAKER) {
                    voIpButtonView.setIcons(R.raw.speaker_to_bt, size);
                }
                break;
            case BLUETOOTH:
                if (this.type == Type.SPEAKER) {
                    ignoreSetState = animateRouteIcon(newType, isSelected, R.raw.bt_to_speaker, size);
                } else if (this.type == Type.CAMERA) {
                    ignoreSetState = true;
                    attachNewButton(R.raw.bt_to_speaker, size, isSelected, newType);
                } else if (this.type != Type.BLUETOOTH) {
                    voIpButtonView.setIcons(R.raw.bt_to_speaker, size);
                }
                break;
        }

        if (!ignoreSetState) {
            voIpButtonView.setSelectedState(isSelected, this.type != null && !fast, newType);
        }
        setText(newType, isSelected);
        this.type = newType;
    }

    private boolean animateRouteIcon(Type destination, boolean selected, int idleIconRes, int size) {
        VoIpButtonView button = voIpButtonView;
        RLottieDrawable icon = selected ? button.selectedIcon : button.unSelectedIcon;
        if (icon.hasPrivateCallArtwork()) {
            attachNewButton(idleIconRes, size, selected, destination);
            return true;
        }
        icon.setOnAnimationEndListener(() -> AndroidUtilities.runOnUIThread(() -> {
            if (type == destination && voIpButtonView == button &&
                    (button.selectedIcon == icon || button.unSelectedIcon == icon)) {
                button.setIcons(idleIconRes, size);
            }
        }));
        icon.start();
        return selected == button.isSelectedState;
    }

    public static class VoIpButtonView extends View {
        private static final int ITEM_SIZE = 52;

        private RLottieDrawable unSelectedIcon;
        private RLottieDrawable selectedIcon;
        private RLottieDrawable singleIcon;
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint whiteCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedPackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final int maxRadius = AndroidUtilities.dp(ITEM_SIZE / 2f);
        private int unselectedRadius = maxRadius;
        private int selectedRadius = 0;
        private boolean isSelectedState = false;
        private int singleIconBackgroundAlphaPercent = 0;
        private OnBtnClickedListener onBtnClickedListener;
        private ValueAnimator animator;
        private final VoIPBackgroundProvider backgroundProvider;

        private RLottieDrawable newIcon(int rawRes, int size) {
            RLottieDrawable icon = new RLottieDrawable(rawRes, size, size, true, null);
            icon.bindPrivateCallIcons(getContext());
            icon.setBounds(0, 0, size, size);
            icon.setMasterParent(this);
            return icon;
        }

        private void setIcons(int rawRes, int size) {
            if (rawRes == R.raw.camera_flip2) {
                singleIcon = newIcon(rawRes, size);
                unSelectedIcon = selectedIcon = null;
            } else {
                singleIcon = null;
                unSelectedIcon = newIcon(rawRes, size);
                selectedIcon = newIcon(rawRes, size);
                selectedIcon.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.MULTIPLY));
            }
        }

        private static void seekIcon(RLottieDrawable icon, int frame) {
            boolean packed = icon.hasPrivateCallArtwork();
            icon.setInvalidateOnProgressSet(packed);
            icon.setCurrentFrame(frame, packed);
        }
        public void setSelectedState(boolean selectedState, boolean animate, Type type) {
            if (animator != null && animator.isRunning()) {
                animator.removeAllUpdateListeners();
                animator.cancel();
                animate = false;
            }
            if (animate) {
                if (singleIcon != null) {
                    if (animator != null) {
                        animator.removeAllUpdateListeners();
                        animator.cancel();
                    }
                    animator = selectedState ? ValueAnimator.ofInt(20, 100) : ValueAnimator.ofInt(100, 20);
                    animator.addUpdateListener(animation -> {
                        singleIconBackgroundAlphaPercent = (int) animation.getAnimatedValue();
                        invalidate();
                    });
                    animator.setDuration(200);
                    animator.start();
                    if (type == Type.CAMERA) {
                        seekIcon(singleIcon, 0);
                        singleIcon.start();
                    }
                } else {
                    if (animator != null) {
                        animator.removeAllUpdateListeners();
                        animator.cancel();
                    }
                    animator = ValueAnimator.ofInt(0, maxRadius);
                    if (selectedState) {
                        unselectedRadius = maxRadius;
                        animator.addUpdateListener(animation -> {
                            selectedRadius = (int) animation.getAnimatedValue();
                            invalidate();
                        });
                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                unselectedRadius = 0; //switched to selected state
                                invalidate();
                            }
                        });
                        animator.setDuration(200);
                        animator.start();
                        seekIcon(selectedIcon, 0);
                        selectedIcon.start();
                    } else {
                        selectedRadius = maxRadius;
                        animator.addUpdateListener(animation -> {
                            unselectedRadius = (int) animation.getAnimatedValue();
                            invalidate();
                        });
                        animator.setDuration(200);
                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                selectedRadius = 0; //switched to NOT selected state
                                invalidate();
                            }
                        });
                        animator.start();
                    }
                }
            } else {
                if (selectedState) {
                    selectedRadius = maxRadius;
                    unselectedRadius = 0;
                    singleIconBackgroundAlphaPercent = 100;
                    if (type == Type.VIDEO || type == Type.MICRO) {
                        seekIcon(selectedIcon, selectedIcon.getFramesCount() - 1);
                    }
                } else {
                    selectedRadius = 0;
                    unselectedRadius = maxRadius;
                    singleIconBackgroundAlphaPercent = 20;
                }
            }
            isSelectedState = selectedState;
            invalidate();
        }

        public interface OnBtnClickedListener {
            void onClicked(View view);
        }

        public void setOnBtnClickedListener(OnBtnClickedListener onBtnClickedListener) {
            this.onBtnClickedListener = onBtnClickedListener;
        }

        public VoIpButtonView(@NonNull Context context, VoIPBackgroundProvider backgroundProvider) {
            super(context);
            this.backgroundProvider = backgroundProvider;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            whiteCirclePaint.setColor(Color.WHITE);

            selectedPackPaint.setColorFilter(new PorterDuffColorFilter(0xff263747, PorterDuff.Mode.SRC_IN));
            maskPaint.setColor(Color.BLACK);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

            darkPaint.setColor(Color.BLACK);
            darkPaint.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP));
            darkPaint.setAlpha(VoIPBackgroundProvider.DARK_LIGHT_DEFAULT_ALPHA);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            backgroundProvider.attach(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            backgroundProvider.detach(this);
            super.onDetachedFromWindow();
        }
        private ValueAnimator pressedScaleAnimator;
        private float pressedScale = 1.0f;

        private void setPressedBtn(boolean pressed) {
            if (pressedScaleAnimator != null) {
                pressedScaleAnimator.cancel();
            }
            pressedScaleAnimator = ValueAnimator.ofFloat(pressedScale, pressed ? 0.8f : 1f);
            pressedScaleAnimator.addUpdateListener(animation -> {
                pressedScale = (float) animation.getAnimatedValue();
                invalidate();
            });
            pressedScaleAnimator.setDuration(150);
            pressedScaleAnimator.start();
        }

        private void drawSelectedIcon(Canvas canvas) {
            if (selectedIcon.hasPrivateCallArtwork()) {
                selectedIcon.draw(canvas, selectedPackPaint);
            } else {
                selectedIcon.setAlpha(255);
                selectedIcon.draw(canvas, maskPaint);
                selectedIcon.setAlpha((int) (255 * VoIPBackgroundProvider.DARK_LIGHT_PERCENT));
                selectedIcon.draw(canvas);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(pressedScale, pressedScale, getMeasuredWidth() / 2f, getMeasuredHeight() / 2f);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float left = getX() + ((View) getParent()).getX();
            float top = getY() + ((View) ((View) getParent()).getParent()).getY();
            backgroundProvider.setLightTranslation(left, top);

            if (singleIcon != null) {
                if (singleIconBackgroundAlphaPercent > 20) {
                    darkPaint.setAlpha((int) (VoIPBackgroundProvider.DARK_LIGHT_DEFAULT_ALPHA * singleIconBackgroundAlphaPercent / 100f));
                    whiteCirclePaint.setAlpha((int) (255 * singleIconBackgroundAlphaPercent / 100f));
                    canvas.drawCircle(cx, cy, maxRadius, whiteCirclePaint);
                    if (singleIcon.hasPrivateCallArtwork()) {
                        int inkAlpha = (singleIconBackgroundAlphaPercent - 20) * 255 / 80;
                        singleIcon.setAlpha(255 - inkAlpha);
                        if (inkAlpha < 255) singleIcon.draw(canvas);
                        selectedPackPaint.setAlpha(inkAlpha);
                        singleIcon.draw(canvas, selectedPackPaint);
                        selectedPackPaint.setAlpha(255);
                        singleIcon.setAlpha(255);
                    } else {
                    singleIcon.draw(canvas, maskPaint);
                    singleIcon.draw(canvas, darkPaint); //dimming icons
                    }
                } else {
                    canvas.drawCircle(cx, cy, maxRadius, backgroundProvider.getLightPaint()); //add a light background
                    if(backgroundProvider.isReveal()) {
                        canvas.drawCircle(cx, cy, maxRadius, backgroundProvider.getRevealPaint());
                    }
                    singleIcon.draw(canvas);
                }
                canvas.restore();
                return;
            }
            if (selectedIcon == null || unSelectedIcon == null) {
                canvas.restore();
                return;
            }

            boolean isUnSelected = unselectedRadius == maxRadius && selectedRadius == 0;
            boolean isSelected = selectedRadius == maxRadius && unselectedRadius == 0;

            if (selectedRadius == maxRadius && unselectedRadius > 0 && unselectedRadius != maxRadius) {
                //in the process of changing from selected to NOT selected.
                canvas.drawCircle(cx, cy, selectedRadius, whiteCirclePaint);
                canvas.drawCircle(cx, cy, unselectedRadius, maskPaint);

                drawSelectedIcon(canvas);

                clipPath.reset();
                clipPath.addCircle(cx, cy, unselectedRadius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                canvas.drawCircle(cx, cy, unselectedRadius, maskPaint); //remove all background
            }

            if (isUnSelected || unselectedRadius > 0) {
                //not selected or in the process of changing from selected to NOT selected
                canvas.drawCircle(cx, cy, unselectedRadius, backgroundProvider.getLightPaint()); //add a light background
                if (backgroundProvider.isReveal()) {
                    canvas.drawCircle(cx, cy, unselectedRadius, backgroundProvider.getRevealPaint());
                }
                unSelectedIcon.draw(canvas);
            }

            if (isSelected || (selectedRadius > 0 && unselectedRadius == maxRadius)) {
                //selected and not in the process of changing or in the process of changing from NOT selected to selected.
                clipPath.reset();
                clipPath.addCircle(cx, cy, selectedRadius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                canvas.drawCircle(cx, cy, selectedRadius, whiteCirclePaint); //circular background
                drawSelectedIcon(canvas);
            }
            canvas.restore();
        }

        private boolean isAnimating() {
            boolean isUnSelected = unselectedRadius == maxRadius && selectedRadius == 0;
            boolean isSelected = selectedRadius == maxRadius && unselectedRadius == 0;
            return !isUnSelected && !isSelected;
        }

        private float startX;
        private float startY;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    setPressedBtn(true);
                    startX = event.getX();
                    startY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    setPressedBtn(false);
                    float endX = event.getX();
                    float endY = event.getY();
                    if (isClick(startX, endX, startY, endY) && !isAnimating()) {
                        if (onBtnClickedListener != null) onBtnClickedListener.onClicked(this);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    setPressedBtn(false);
                    break;
            }
            return true;
        }

        private boolean isClick(float startX, float endX, float startY, float endY) {
            float differenceX = Math.abs(startX - endX);
            float differenceY = Math.abs(startY - endY);
            return !(differenceX > AndroidUtilities.dp(48) || differenceY > AndroidUtilities.dp(48));
        }
    }
}
