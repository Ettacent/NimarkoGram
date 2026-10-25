/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import org.telegram.messenger.R;

public final class AnimatedCallIcons {
    private AnimatedCallIcons() {}

    public static AnimatedIconSpec get(int rawRes) {
        if (rawRes == R.raw.call_mute) {
            return AnimatedIconSpec.pair(R.drawable.calls_unmute, R.drawable.calls_mute_mini, 3, 17).withAlignedTransition().withScale(.5f).withMotion(CallIconMotion.TOGGLE);
        }
        if (rawRes == R.raw.video_stop) {
            return AnimatedIconSpec.pair(R.drawable.calls_video, R.drawable.calls_camera_mini, 3, 17).withAlignedTransition().withScale(.5f).withMotion(CallIconMotion.TOGGLE);
        }
        if (rawRes == R.raw.camera_flip2) {
            return AnimatedIconSpec.pulse(R.drawable.camera_revert1).withScale(.5f).withMotion(CallIconMotion.CAMERA_BUTTON);
        }
        if (rawRes == R.raw.camera_flip) {
            return AnimatedIconSpec.pulse(R.drawable.camera_revert1).withPulseFrames(0, 17, 18, 38).withMotion(CallIconMotion.CAMERA);
        }
        if (rawRes == R.raw.speaker_to_bt) {
            return AnimatedIconSpec.pair(R.drawable.calls_speaker, R.drawable.calls_bluetooth, 0, 30).withScale(.5f).withMotion(CallIconMotion.TOGGLE);
        }
        if (rawRes == R.raw.bt_to_speaker) {
            return AnimatedIconSpec.pair(R.drawable.calls_bluetooth, R.drawable.calls_speaker, 0, 20).withScale(.5f).withMotion(CallIconMotion.BLUETOOTH_TO_SPEAKER);
        }
        if (rawRes == R.raw.speaker) {
            return AnimatedIconSpec.frames(new int[]{
                    R.drawable.calls_speaker, AnimatedIconSpec.slashed(R.drawable.calls_speaker),
                    AnimatedIconSpec.slashed(R.drawable.calls_speaker), R.drawable.calls_speaker, R.drawable.calls_speaker
            }, new float[]{0, 16, 17, 32, 33});
        }
        if (rawRes == R.raw.voice_outlined) {
            return AnimatedIconSpec.frames(new int[]{
                    R.drawable.calls_unmute, R.drawable.calls_mute_mini, R.drawable.calls_unmute
            }, new float[]{0, 12, 23}).withAlignedTransition();
        }
        if (rawRes == R.raw.voice_muted) {
            return AnimatedIconSpec.frames(new int[]{
                    R.drawable.calls_unmute, R.drawable.calls_mute_mini, R.drawable.calls_unmute
            }, new float[]{0, 14, 28}).withAlignedTransition();
        }
        if (rawRes == R.raw.voice_outlined2) {
            return AnimatedIconSpec.frames(new int[]{
                    R.drawable.msg_handdown, R.drawable.calls_mute_mini, R.drawable.calls_mute_mini,
                    R.drawable.calls_unmute, R.drawable.calls_unmute, R.drawable.calls_mute_mini, R.drawable.msg_handdown
            }, new float[]{0, 20, 21, 41, 43, 63, 83}).withAlignedTransition();
        }
        if (rawRes == R.raw.voice_mini) {
            return AnimatedIconSpec.frames(new int[]{
                    R.drawable.msg_handdown, R.drawable.calls_mute_mini,
                    R.drawable.calls_mute_mini, R.drawable.calls_unmute,
                    R.drawable.calls_unmute, R.drawable.calls_mute_mini,
                    R.drawable.calls_mute_mini, R.drawable.msg_handdown,
                    R.drawable.calls_unmute, R.drawable.msg_handdown
            }, new float[]{0, 35, 36, 68, 69, 98, 99, 135, 136, 171}).withAlignedTransition();
        }
        if (rawRes == R.raw.voip_filled) {
            return AnimatedIconSpec.frames(new int[]{
                    R.drawable.msg_handdown, R.drawable.calls_mute_mini,
                    R.drawable.calls_mute_mini, R.drawable.calls_unmute,
                    R.drawable.calls_unmute, R.drawable.calls_mute_mini,
                    R.drawable.calls_mute_mini, R.drawable.msg_handdown,
                    R.drawable.calls_unmute, R.drawable.msg_handdown,
                    R.drawable.input_notify_on, R.drawable.input_notify_off,
                    R.drawable.input_notify_on, R.drawable.calls_mute_mini,
                    R.drawable.input_notify_on, R.drawable.msg_handdown,
                    R.drawable.input_notify_off, R.drawable.msg_handdown,
                    R.drawable.input_notify_off, R.drawable.input_notify_on,
                    R.drawable.input_notify_off, R.drawable.calls_mute_mini,
                    R.drawable.ic_action_play, R.drawable.calls_mute_mini
            }, new float[]{0, 35, 36, 68, 69, 98, 99, 135, 136, 172, 173, 201,
                    202, 236, 237, 273, 274, 310, 311, 343, 344, 375, 376, 403}).withAlignedTransition();
        }
        if (rawRes == R.raw.hand_1) {
            return AnimatedIconSpec.pulse(R.drawable.msg_handdown)
                    .withPulseFrames(0, 119, 120, 239, 240, 419, 420, 539, 540, 719);
        }
        if (rawRes == R.raw.hand_2) {
            return AnimatedIconSpec.pulse(R.drawable.msg_handdown)
                    .withPulseFrames(0, 119, 120, 239, 240, 419, 420, 539);
        }
        if (rawRes == R.raw.call_accept) {
            return AnimatedIconSpec.pulse(R.drawable.msg_calls).withPulseFrames(0, 45).withScale(.5f).withMotion(CallIconMotion.ACCEPT);
        }
        if (rawRes == R.raw.star_stroke) {
            return AnimatedIconSpec.single(R.drawable.ic_rating_star);
        }
        if (rawRes == R.raw.star_fill) {
            return AnimatedIconSpec.single(R.drawable.ic_rating_star_filled);
        }
        if (rawRes == R.raw.safari_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_web_safari).withPulseFrames(60, 90).withScale(.48f);
        }
        if (rawRes == R.raw.edge_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_web_edge).withPulseFrames(60, 110).withScale(.48f);
        }
        if (rawRes == R.raw.chrome_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_web_chrome).withPulseFrames(60, 90).withScale(.48f);
        }
        if (rawRes == R.raw.firefox_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_web_firefox).withPulseFrames(60, 120).withScale(.48f);
        }
        if (rawRes == R.raw.ubuntu_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_desktop_other).withPulseFrames(60, 90).withScale(.48f);
        }
        if (rawRes == R.raw.linux_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_desktop_other).withPulseFrames(60, 100).withScale(.48f);
        }
        if (rawRes == R.raw.ipad_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_tablet_ios).withPulseFrames(60, 100).withScale(.48f);
        }
        if (rawRes == R.raw.iphone_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_phone_ios).withPulseFrames(60, 100).withScale(.48f);
        }
        if (rawRes == R.raw.windows_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_desktop_win).withPulseFrames(60, 130).withScale(.48f);
        }
        if (rawRes == R.raw.mac_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_desktop_osx).withPulseFrames(60, 100).withScale(.48f);
        }
        if (rawRes == R.raw.android_30) {
            return AnimatedIconSpec.pulse(R.drawable.device_phone_android).withPulseFrames(60, 100).withScale(.48f);
        }
        return null;
    }

    public static int[] drawableIds() {
        return new int[]{
                R.drawable.calls_unmute, R.drawable.calls_mute_mini, R.drawable.calls_video,
                R.drawable.calls_camera_mini, R.drawable.camera_revert1, R.drawable.calls_speaker,
                R.drawable.calls_bluetooth, R.drawable.msg_handdown, R.drawable.input_notify_on,
                R.drawable.input_notify_off, R.drawable.ic_action_play, R.drawable.msg_calls,
                R.drawable.ic_rating_star, R.drawable.ic_rating_star_filled,
                R.drawable.device_web_safari, R.drawable.device_web_edge, R.drawable.device_web_chrome,
                R.drawable.device_web_firefox, R.drawable.device_desktop_other, R.drawable.device_tablet_ios,
                R.drawable.device_phone_ios, R.drawable.device_desktop_win, R.drawable.device_desktop_osx,
                R.drawable.device_phone_android
        };
    }
}
