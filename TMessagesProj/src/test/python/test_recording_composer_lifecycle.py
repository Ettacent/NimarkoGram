"""Run production composer decisions on the JDK; guard Android animation wiring.

No APK/SDK build is needed. These tests do not claim to render Android frames.
"""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / "main"
ENTER = (SRC / "java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text()
CONTAINER = (SRC / "java/org/telegram/ui/Components/chat/ChatInputViewsContainer.java").read_text()


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class RecordingComposerLifecycleTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_lifecycle_accessibility_timing_and_capture_decisions(self):
        enter_methods = "\n".join(method(ENTER, signature) for signature in (
            "private boolean isSeparatedRecordingUiVisible()",
            "private boolean isSeparatedRecordingExitInProgress()",
            "private void updateRecordingAttachButton()",
            "private long getRecordTextRestoreDelay(",
            "private long getRecordDeleteFadeDelay(",
            "private boolean useFastRecordExit(",
        ))
        container_methods = "\n".join(method(CONTAINER, signature) for signature in (
            "private int getLeadingComposerAlpha(",
            "private int getTrailingComposerAlpha(",
            "public void syncComposerDrawableAlphas()",
            "public void setInputBubbleAlpha(",
            "public void setDrawInputCenterBackground(",
        ))
        java = r'''
public class RecordingComposerHarness {
    static final int VISIBLE=0, GONE=8;
    static final int RECORD_STATE_PREPARING=2, RECORD_STATE_CANCEL_BY_TIME=3;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static class View {
        int visibility=GONE;
        float alpha=1, scaleX=1, scaleY=1;
        boolean enabled=true, clickable=true;
        int getVisibility() { return visibility; }
        void setAlpha(float x) { alpha=x; }
        void setScaleX(float x) { scaleX=x; }
        void setScaleY(float x) { scaleY=x; }
        void setEnabled(boolean x) { enabled=x; }
        void setClickable(boolean x) { clickable=x; }
    }
    static class Animator {
        long delay, duration=150;
        boolean running;
        void cancel() { running=false; }
        boolean isRunning() { return running; }
        long getStartDelay() { return delay; }
        long getDuration() { return duration; }
    }
    static class RLottieDrawable extends Animator {}
    static class Enter {
        boolean separatedComposerLayout=true, recordingAudioVideo, recordIsCanceled;
        boolean recordingAttachButtonDisabled;
        int recordInterfaceState;
        View recordPanel=new View(), recordedAudioPanel=new View(), attachButton=new View();
        Animator recordPannelAnimation, attachButtonAnimator;
        float attachButtonAlpha=1;
        ENTER_METHODS
    }
    static class Drawable {
        int alpha;
        void setAlpha(int value) { alpha=value; }
    }
    static class Container {
        boolean drawInputBackground=true, drawInputCenterBackground=true;
        int inputBubbleAlpha=255;
        float separatedComposerProgress=1, recordingComposerProgress;
        float leadingComposerExpansionProgress, leadingVisibility=1, takeover;
        Drawable blurredBackgroundDrawable=new Drawable();
        Drawable leadingComposerDrawable=new Drawable(), trailingComposerDrawable=new Drawable();
        float getLeadingComposerVisibility() { return leadingVisibility; }
        float getTrailingComposerTakeoverProgress() { return takeover; }
        void invalidate() {}
        CONTAINER_METHODS
    }
    public static void main(String[] args) {
        // Exercise actual ownership/disabled-state methods for all lifecycle combinations.
        for (int state=0; state<32; state++) {
            Enter c=new Enter();
            c.recordingAudioVideo=(state & 1)!=0;
            c.recordInterfaceState=(state & 2)!=0 ? 1 : 0;
            c.recordPanel.visibility=(state & 4)!=0 ? VISIBLE : GONE;
            c.recordedAudioPanel.visibility=(state & 8)!=0 ? VISIBLE : GONE;
            c.recordIsCanceled=(state & 16)!=0;
            boolean owned=(state & 15)!=0;
            check(c.isSeparatedRecordingUiVisible()==owned, "capture/entry/bin/preview ownership");
            c.updateRecordingAttachButton();
            check(c.attachButton.enabled==!owned && c.attachButton.clickable==!owned,
                "accessibility must match activation state");
            check(c.attachButton.alpha==1 && c.attachButton.scaleX==1 && c.attachButton.scaleY==1,
                "paperclip normal size and opacity");
            c.separatedComposerLayout=false;
            c.updateRecordingAttachButton();
            check(c.attachButton.enabled && !c.isSeparatedRecordingUiVisible(), "native fallback");
        }
        Enter c=new Enter();
        c.recordingAudioVideo=true;
        c.recordPanel.visibility=VISIBLE;
        c.attachButton.alpha=0;
        c.attachButton.scaleX=c.attachButton.scaleY=0.5f;
        c.attachButtonAnimator=new Animator(); c.attachButtonAnimator.running=true;
        c.updateRecordingAttachButton();
        check(c.attachButtonAnimator==null && c.attachButton.alpha==1 && c.attachButton.scaleX==1,
            "old attach fade retired on entry");
        c.recordingAudioVideo=false; c.recordIsCanceled=true;
        check(c.isSeparatedRecordingExitInProgress(), "cancel owns UI after capture stops");
        c.updateRecordingAttachButton();
        check(!c.attachButton.enabled, "trash cannot open attachment modal");
        c.recordPanel.visibility=GONE;
        c.recordedAudioPanel.visibility=VISIBLE;
        c.recordPannelAnimation=new Animator(); c.recordPannelAnimation.running=true;
        check(c.isSeparatedRecordingExitInProgress(), "preview deletion owns UI");
        c.updateRecordingAttachButton();
        check(!c.attachButton.clickable, "preview deletion cannot open attachment modal");
        c.recordPannelAnimation=null; c.recordedAudioPanel.visibility=GONE;
        c.updateRecordingAttachButton();
        check(c.attachButton.enabled && c.attachButton.clickable, "completion restores activation");
        c.recordingAudioVideo=true; c.recordIsCanceled=false; c.recordPanel.visibility=VISIBLE;
        c.updateRecordingAttachButton();
        check(!c.attachButton.enabled, "subsequent recording gets fresh ownership");
        check(!c.useFastRecordExit(true, RECORD_STATE_PREPARING), "rapid re-pause still prepares preview");
        check(c.useFastRecordExit(true, 0), "early cancellation remains fast");
        check(c.useFastRecordExit(false, RECORD_STATE_CANCEL_BY_TIME), "timed exit remains fast");
        RLottieDrawable bin=new RLottieDrawable(); bin.duration=866;
        Animator fade=new Animator(); fade.duration=150;
        for (long standard : new long[]{600,700}) {
            fade.delay=c.getRecordDeleteFadeDelay(bin,standard,fade.duration);
            long restore=c.getRecordTextRestoreDelay(fade,300);
            check(restore>=bin.duration, "trash completes before hint returns");
            for (int hz : new int[]{60,90,120,144}) {
                for (double t=0;t<1200;t+=1000d/hz) {
                    check(!(t>restore && t<fade.delay+fade.duration), "no text/trash overlap");
                }
            }
        }
        c.separatedComposerLayout=false;
        check(c.getRecordDeleteFadeDelay(bin,600,150)>=bin.duration, "native bin finishes before exit");
        check(c.getRecordTextRestoreDelay(fade,300)>=fade.delay+fade.duration, "native text waits for bin exit too");
        check(c.useFastRecordExit(true,RECORD_STATE_PREPARING), "native branch unchanged");
        Container v=new Container();
        for (int frame=0;frame<=100;frame++) {
            v.recordingComposerProgress=frame/100f;
            v.syncComposerDrawableAlphas();
            check(v.leadingComposerDrawable.alpha==255, "recording must never fade leading island");
            check(v.trailingComposerDrawable.alpha==Math.round(255*(1-frame/100f)), "trailing handoff");
        }
        v.setInputBubbleAlpha(200);
        check(v.trailingComposerDrawable.alpha==0, "global alpha setter must not resurrect hidden trailing island");
        check(v.leadingComposerDrawable.alpha==200, "visible paperclip retained");
        v.leadingVisibility=0; v.leadingComposerExpansionProgress=1;
        v.syncComposerDrawableAlphas();
        check(v.leadingComposerDrawable.alpha==0, "collapsed leading island excluded from capture");
        v.setDrawInputCenterBackground(false);
        check(v.blurredBackgroundDrawable.alpha==0, "hidden center excluded from capture");
        v.drawInputBackground=false; v.syncComposerDrawableAlphas();
        check(v.leadingComposerDrawable.alpha==0 && v.trailingComposerDrawable.alpha==0,
            "hidden composer excluded from capture");
        v.drawInputBackground=true; v.setDrawInputCenterBackground(true);
        v.recordingComposerProgress=0; v.leadingVisibility=1; v.leadingComposerExpansionProgress=0;
        v.takeover=1; v.syncComposerDrawableAlphas();
        check(v.trailingComposerDrawable.alpha==0, "slow-mode takeover excluded from capture");
        v.separatedComposerProgress=0; v.syncComposerDrawableAlphas();
        check(v.leadingComposerDrawable.alpha==0 && v.trailingComposerDrawable.alpha==0,
            "native composer has no captured side islands");
        System.out.println("PASS");
    }
}
'''.replace("ENTER_METHODS", enter_methods).replace("CONTAINER_METHODS", container_methods)
        with tempfile.TemporaryDirectory(prefix="recording-composer-test-") as folder:
            path = Path(folder) / "RecordingComposerHarness.java"
            path.write_text(java)
            compiled = subprocess.run(["javac", str(path)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", folder, "RecordingComposerHarness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS", result.stdout)

    def test_attachment_touch_and_click_share_the_lifecycle_guard(self):
        attach = ENTER.split("attachButton = new ImageView(context)", 1)[1].split("aiButton =", 1)[0]
        self.assertIn("if (isSeparatedRecordingUiVisible()) return true;", attach)
        click = attach.split("attachButton.setOnClickListener", 1)[1]
        self.assertLess(click.index("isSeparatedRecordingUiVisible()"), click.index("delegate.didPressAttachButton()"))
        self.assertIn("setContentDescription(getString(R.string.AccDescrAttachButton))", attach)

    def test_no_recording_path_hides_the_separated_paperclip(self):
        entry = method(ENTER, "protected void updateRecordInterface(")
        self.assertIn("if (!separatedComposerLayout) {\n                    viewTransition.playTogether(", entry)
        self.assertIn("if (!separatedComposerLayout && attachButton != null", entry)
        delete = method(ENTER, "private void hideRecordedAudioPanel(")
        self.assertEqual(delete.count("if (attachButton != null && !separatedComposerLayout)"), 2)
        self.assertIn("if (fromDraft && attachButton != null && !separatedComposerLayout)", ENTER)
        params = method(ENTER, "private void updateAttachLayoutParams()")
        self.assertIn("isSeparatedRecordingUiVisible()\n                        ? 1f", params)

    def test_fast_exit_fades_label_before_restoring_text(self):
        exit_code = ENTER.split("if (useFastRecordExit(shouldShowFastTransition, recordState))", 1)[1].split(
            "else if (recordState == RECORD_STATE_PREPARING)", 1)[0]
        self.assertIn("ObjectAnimator.ofFloat(slideText, View.ALPHA, 0f)", exit_code)
        self.assertIn("messageEditText.setAlpha(0f)", exit_code)
        self.assertIn("restoreText.setStartDelay(150)", exit_code)
        self.assertIn("runningAnimationAudio.setDuration(150)", exit_code)

    def test_gesture_started_during_exit_cannot_activate_on_late_release(self):
        button = ENTER.split("audioVideoButtonContainer = new FrameLayout(context)", 1)[1].split(
            "createRecordCircle();", 1)[0]
        self.assertIn("recordingExitGestureBlocked = isSeparatedRecordingExitInProgress()", button)
        blocked = button.split("if (recordingExitGestureBlocked)", 1)[1]
        self.assertIn("MotionEvent.ACTION_UP", blocked)
        self.assertIn("MotionEvent.ACTION_CANCEL", blocked)
        self.assertIn("recordingExitGestureBlocked = false", blocked)
        self.assertIn("return true;", blocked)

    def test_reset_retires_animators_and_restores_state(self):
        reset = method(ENTER, "public void reset() {\n        if (separatedComposerLayout)")
        for name in ("runningAnimationAudio", "recordPannelAnimation"):
            self.assertLess(reset.index(name + ".removeAllListeners()"), reset.index(name + ".cancel()"))
            self.assertLess(reset.index(name + ".cancel()"), reset.index("cancelRecordInterfaceInternal()"))
        cleanup = method(ENTER, "private void cancelRecordInterfaceInternal()")
        self.assertIn("recordInterfaceState = 0", cleanup)
        self.assertLess(cleanup.index("recordPanel.setVisibility(GONE)"), cleanup.index("isRecordingStateChanged()"))
        self.assertLess(reset.index("hideRecordedAudioPanelInternal()"), reset.index("restoreSeparatedRecordingControls()"))
        restore = method(ENTER, "private void restoreSeparatedRecordingControls()")
        self.assertIn("emojiButtonAlpha = emojiButtonRestricted ? 0.5f : 1f", restore)
        self.assertIn("emojiButtonScale = 1f", restore)
        for property_name in ("Alpha", "ScaleX", "ScaleY"):
            self.assertIn("audioVideoButtonContainer.set" + property_name + "(1f)", restore)
        self.assertIn("checkSendButton(false)", restore)

    def test_normal_radius_applies_to_exit_and_preview_not_only_steady_state(self):
        circle = ENTER.split("public class RecordCircle", 1)[-1]
        self.assertEqual(circle.count("dp(separatedComposerLayout ? 0 : 16) * progressToSeekbarStep1"), 2)
        # Geometry and the entire cancellation curve execute in the JVM harness;
        # don't assert a hand-written idle-button formula unrelated to production.
        draw = method(circle, "protected void onDraw(Canvas canvas)")
        self.assertIn("(circleRadius + circleRadiusAmplitude * amplitude)", draw)
        self.assertNotIn("separatedComposerLayout ? 0 : circleRadiusAmplitude", draw)
        animation = json.loads((SRC / "res/raw/chat_audio_record_delete_3.json").read_text())
        # Dot-to-bin morph completes before the existing outer exit finishes.
        self.assertGreater((animation["op"] - animation["ip"]) / animation["fr"] * 1000, 600)
        self.assertNotIn("alpha = 1f", method(ENTER, "public void playDeleteAnimation()"))

    def test_timer_cancel_coordinates_and_hit_bounds_agree(self):
        slide = ENTER.split("private class SlideTextView", 1)[1].split("public class TimerView", 1)[0]
        self.assertIn("recordTimeContainer.getX() + recordTimerView.getX() - getX()", slide)
        self.assertIn("TextUtils.ellipsize(cancelString, bluePaint, layoutWidth", slide)
        self.assertIn("cancelRect.left = Math.max", slide)
        self.assertIn("cancelRect.right = Math.min", slide)
        self.assertGreater(slide.index("canvas.clipRect(actionLeft, 0, actionRight"),
                           slide.index("selectableBackground.draw(canvas)"))
        self.assertIn("new RecordingCancelFeedbackDrawable(pressColor)", slide)
        self.assertIn("selectableBackground.setBounds(cancelFeedbackBounds)", slide)
        self.assertIn("getRecordingCancelLeft(getMeasuredWidth(), actionLeft, actionRight", slide)
        self.assertNotIn("actionLeft + (actionRight - actionLeft - cancelLayout.getWidth()) / 2f", slide)
        self.assertIn("canvas.clipPath(cancelPillClip)", slide)
        self.assertIn("recordPanel.getWidth() - getX()", slide)
        self.assertNotIn("selectorCenter - w", slide)
        panel = method(ENTER, "private void createRecordPanel()")
        self.assertIn("Gravity.NO_GRAVITY, separatedComposerLayout ? 0 : 45", panel)
        self.assertIn("recordTimeContainer.setPadding(dp(separatedComposerLayout ? 8 : 13)", panel)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_measured_cancel_position_lock_motion_and_real_touch_handler(self):
        slide = ENTER.split("private class SlideTextView", 1)[1].split("public class TimerView", 1)[0]
        origin = slide.split("            float slideX =", 1)[1].split("            float offsetY =", 1)[0]
        hit = slide.split("            float xi;", 1)[1].split("            if (cancelToProgress > 0)", 1)[0]
        touch = method(slide, "public boolean onTouchEvent(MotionEvent event)")
        java = r'''
public class CancelGeometryHarness {
    static float density=3;
    static int dp(float x) { return (int)Math.ceil(x*density); }
    static class Rect {
        int left,top,right,bottom;
        void set(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
        void inset(int x,int y) { left+=x;right-=x;top+=y;bottom-=y; }
        boolean contains(int x,int y) { return x>=left && x<right && y>=top && y<bottom; }
    }
    static class MotionEvent {
        static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
        int action; float x,y;
        MotionEvent(int a,float xx,float yy) { action=a;x=xx;y=yy; }
        int getAction() { return action; }
        float getX() { return x; } float getY() { return y; }
    }
    static class Layout {
        int width,height; float offset;
        int getWidth() { return width; } int getHeight() { return height; }
        float getPrimaryHorizontal(int i) { return offset; }
    }
    static class Selector { void setHotspot(int x,int y) {} }
    static void check(boolean b,String message) { if(!b) throw new AssertionError(message); }
    static void near(float actual,float expected,String label) {
        check(Math.abs(actual-expected)<0.01f,label+": "+actual+" != "+expected);
    }
    GEOMETRY
    boolean separatedComposerLayout=true,pressed,enabled=true,visualPressed;
    float width=750,height=132,actionLeft=276,actionRight=726;
    float slideToCancelWidth=330,cancelWidth=180,xOffset=0,slideProgress=1,cancelToProgress=1;
    int cancelCharOffset=-1,clicks;
    Layout cancelLayout=new Layout(),slideToLayout=new Layout();
    Rect cancelRect=new Rect(); Selector selectableBackground=new Selector();
    int getMeasuredWidth() { return (int)width; } int getMeasuredHeight() { return (int)height; }
    boolean isEnabled() { return enabled; }
    void setPressed(boolean b) { visualPressed=b; }
    void onCancelButtonPressed() { clicks++; }
    TOUCH
    float drawPosition() {
        boolean enableTransition=cancelCharOffset>=0;
        float slideX = ORIGIN
        float offsetY=enableTransition ? 0 : cancelToProgress*dp(12);
        float xi; HIT
        return xi;
    }
    boolean event(int action,float x,float y) { return onTouchEvent(new MotionEvent(action,x,y)); }
    public static void main(String[] args) {
        CancelGeometryHarness c=new CancelGeometryHarness();
        c.cancelLayout.width=180;c.cancelLayout.height=54;
        // Actual 1080px footage: pill 165..915, 750px wide. Old remainder-center
        // result was ~666px. Whole-pill center is 540px, but this timer
        // requires 27px extra clearance to retain BOTH 36px rounded end pads.
        near(165+c.drawPosition()+90,567,"video locked center with symmetric end pads");
        check(c.cancelRect.contains(375,66),"visual center is actionable");
        check(!c.cancelRect.contains(555,66),"former off-center target edge no longer active");
        int cases=0;
        for(float d:new float[]{1,1.5f,2,2.75f,3,4}) {
            density=d;
            for(int panelDp:new int[]{180,250,320,500}) {
                for(int timerDp:new int[]{42,62,84}) {
                    for(int textDp:new int[]{40,60,100,160}) {
                        c.width=dp(panelDp);c.height=dp(44);
                        // Separated row: 8 padding + 28 dot/bin + 6 timer gap.
                        c.actionLeft=dp(42)+dp(timerDp)+dp(8);
                        c.actionRight=c.width-dp(8);
                        c.cancelWidth=dp(textDp);
                        c.cancelLayout.width=Math.max(1,Math.min((int)c.cancelWidth,(int)(c.actionRight-c.actionLeft)-dp(24)));
                        c.cancelLayout.height=dp(18);
                        c.slideToCancelWidth=dp(110);
                        c.slideToLayout.offset=dp(45);
                        c.xOffset=dp(3);
                        float centered=(c.width-c.cancelLayout.width)/2f;
                        float padding=Math.min(dp(12),Math.max(0,(c.actionRight-c.actionLeft-c.cancelLayout.width)/2f));
                        float target=Math.max(c.actionLeft+padding,Math.min(centered,c.actionRight-padding-c.cancelLayout.width));
                        for(int mode:new int[]{-1,2}) {
                            c.cancelCharOffset=mode;c.cancelToProgress=0;
                            float start=c.drawPosition();
                            for(int frame=0;frame<=100;frame++) {
                                c.cancelToProgress=frame/100f;
                                float actual=c.drawPosition();
                                near(actual,start+(target-start)*c.cancelToProgress,"continuous single lock trajectory");
                            }
                            near(c.drawPosition(),target,"measured safe-zone clamp");
                            if(centered>=c.actionLeft+padding && centered+c.cancelLayout.width<=c.actionRight-padding) {
                                near(c.drawPosition()+c.cancelLayout.width/2f,c.width/2f,"center stays fixed when it fits");
                            }
                            check(c.cancelRect.left>=c.actionLeft && c.cancelRect.right<=c.actionRight,"hitbox safe zone");
                            check(c.cancelRect.top>=0 && c.cancelRect.bottom<=c.height,"hitbox height");
                            int cx=(int)(c.drawPosition()+c.cancelLayout.width/2f),cy=(int)c.height/2;
                            check(c.event(MotionEvent.ACTION_DOWN,cx,cy),"center down");
                            check(c.event(MotionEvent.ACTION_UP,cx,cy),"center up");
                            check(!c.pressed && !c.visualPressed,"press retired after click");
                            int clicks=c.clicks;
                            c.event(MotionEvent.ACTION_DOWN,cx,cy);
                            c.event(MotionEvent.ACTION_CANCEL,cx,cy);
                            c.event(MotionEvent.ACTION_UP,cx,cy);
                            check(c.clicks==clicks,"cancelled gesture never activates");
                            c.event(MotionEvent.ACTION_DOWN,cx,cy);
                            c.event(MotionEvent.ACTION_MOVE,0,cy);
                            c.event(MotionEvent.ACTION_UP,cx,cy);
                            check(c.clicks==clicks,"drag outside cannot reactivate on return");
                            cases++;
                        }
                    }
                }
            }
            near(dp(8)+dp(28)/2f,dp(44)/2f,"live dot/trash matches preview delete center");
        }
        System.out.println("PASS: "+cases+" measured layouts, lock trajectories, hitboxes and gesture cancellation");
    }
}
'''.replace("GEOMETRY", method(ENTER, "private static float getRecordingCancelLeft(")) \
            .replace("TOUCH", touch).replace("ORIGIN", origin).replace("HIT", hit)
        with tempfile.TemporaryDirectory(prefix="cancel-geometry-test-") as folder:
            path = Path(folder) / "CancelGeometryHarness.java"
            path.write_text(java)
            result = subprocess.run(["javac", str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", folder, "CancelGeometryHarness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS:", result.stdout)
            # Negative control: the previous remainder-centered target must fail
            # the measured video case, not merely a source-string assertion.
            wrong = java.replace("(panelWidth - labelWidth) / 2f", "(contentLeft + contentRight - labelWidth) / 2f")
            self.assertNotEqual(java, wrong)
            path.write_text(wrong)
            subprocess.run(["javac", str(path)], check=True, capture_output=True, text=True)
            negative = subprocess.run(["java", "-cp", folder, "CancelGeometryHarness"], capture_output=True, text=True)
            self.assertNotEqual(negative.returncode, 0)
            self.assertIn("video locked center", negative.stderr)

    def test_draw_does_not_resurrect_hidden_islands(self):
        draw = method(CONTAINER, "private void updateComposerBackground(")
        self.assertNotIn("leadingComposerDrawable.setAlpha(inputBubbleAlpha)", draw)
        self.assertNotIn("trailingComposerDrawable.setAlpha(inputBubbleAlpha)", draw)
        self.assertLess(draw.index("leadingDrawable.setAlpha(leadingAlpha)"), draw.index("if (leadingAlpha > 0)"))
        self.assertLess(draw.index("trailingDrawable.setAlpha(trailingAlpha)"), draw.index("if (trailingAlpha > 0)"))
        self.assertIn("syncComposerDrawableAlphas();", draw)


if __name__ == "__main__":
    unittest.main()
