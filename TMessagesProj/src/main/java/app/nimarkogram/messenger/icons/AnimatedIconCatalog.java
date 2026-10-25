/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import org.telegram.messenger.R;

public final class AnimatedIconCatalog {
    private AnimatedIconCatalog() {}

    private static final AnimatedIconSpec MSG_EMOJI_SMILES = AnimatedIconSpec.pulse(R.drawable.msg_emoji_smiles).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_smiles));
    private static final AnimatedIconSpec MSG_EMOJI_CAT = AnimatedIconSpec.pulse(R.drawable.msg_emoji_cat).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_cat));
    private static final AnimatedIconSpec MSG_EMOJI_FOOD = AnimatedIconSpec.pulse(R.drawable.msg_emoji_food).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_food));
    private static final AnimatedIconSpec MSG_EMOJI_ACTIVITIES = AnimatedIconSpec.pulse(R.drawable.msg_emoji_activities).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_activities));
    private static final AnimatedIconSpec MSG_EMOJI_TRAVEL = AnimatedIconSpec.pulse(R.drawable.msg_emoji_travel).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_travel));
    private static final AnimatedIconSpec MSG_EMOJI_OBJECTS = AnimatedIconSpec.pulse(R.drawable.msg_emoji_objects).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_objects));
    private static final AnimatedIconSpec MSG_EMOJI_OTHER = AnimatedIconSpec.pulse(R.drawable.msg_emoji_other).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_other));
    private static final AnimatedIconSpec MSG_EMOJI_FLAGS = AnimatedIconSpec.pulse(R.drawable.msg_emoji_flags).withMotion(NativeIconMotion.forRaw(R.raw.msg_emoji_flags));
    private static final AnimatedIconSpec PLAYER_PREV = AnimatedIconSpec.pulse(R.drawable.ic_action_previous).withColorLayer("Triangle 3").withPulseFrames(0f, 24f);
    private static final AnimatedIconSpec WRITE_CONTACTS_FAB_ICON = AnimatedIconSpec.frames(new int[]{R.drawable.msg_edit, R.drawable.msg_edit, R.drawable.filled_new_contact_24, R.drawable.filled_new_contact_24}, new float[]{0f, 29f, 35f, 48f}).withPulseFrames(0f, 29f, 35f, 48f).withScale(0.46f);
    private static final AnimatedIconSpec PASSCODE_LOCK = AnimatedIconSpec.frames(new int[]{R.drawable.menu_unlock, R.drawable.ic_lock_white, R.drawable.ic_lock_white, R.drawable.menu_unlock}, new float[]{0f, 36f, 38f, 70f}).withPulseFrames(0f, 36f, 38f, 70f).withScale(0.76f).withAlignedTransition();
    private static final AnimatedIconSpec UNLOCK_ICON = AnimatedIconSpec.pulse(R.drawable.ic_lock_white).withPulseFrames(0f, 40f).withScale(0.6f);
    private static final AnimatedIconSpec NOTIFY_TOGGLE = AnimatedIconSpec.frames(new int[]{R.drawable.input_notify_off, R.drawable.input_notify_on, R.drawable.input_notify_on, R.drawable.input_notify_off}, new float[]{0f, 39f, 40f, 79f}).withAlignedTransition();
    private static final AnimatedIconSpec SUN = AnimatedIconSpec.frames(new int[]{R.drawable.menu_night_mode_24, R.drawable.menu_day_mode_24}, new float[]{0f, 35f}).withColorLayer("Sunny").withAlignedTransition();
    private static final AnimatedIconSpec SUN_OUTLINE = AnimatedIconSpec.frames(new int[]{R.drawable.menu_night_mode_24, R.drawable.menu_day_mode_24}, new float[]{0f, 35f}).withColorLayer("Sunny").withAlignedTransition();
    private static final AnimatedIconSpec MEDIA_MUTE_UNMUTE = AnimatedIconSpec.frames(new int[]{R.drawable.media_unmute, R.drawable.media_mute, R.drawable.media_mute, R.drawable.media_unmute, R.drawable.media_unmute}, new float[]{0f, 19f, 20f, 42f, 43f}).withAlignedTransition();
    private static final AnimatedIconSpec DOWNLOAD_ARROW = AnimatedIconSpec.frames(new int[]{R.drawable.ng_file_download, R.drawable.ng_media_cancel, R.drawable.ng_media_cancel, R.drawable.ng_file_download}, new float[]{0f, 14f, 15f, 29f}).withAlignedTransition();
    private static final AnimatedIconSpec BOT_WEBVIEW_SHEET_TO_CROSS = AnimatedIconSpec.frames(new int[]{R.drawable.bot_webview, R.drawable.bot_webview, R.drawable.ic_close_white}, new float[]{0f, 1f, 21f});
    private static final AnimatedIconSpec MSG_STORIES_SAVED = AnimatedIconSpec.pulse(R.drawable.msg_stories_saved).withPulseFrames(0f, 19f, 20f, 39f);
    private static final AnimatedIconSpec MSG_STORIES_ARCHIVE = AnimatedIconSpec.pulse(R.drawable.msg_stories_archive).withPulseFrames(0f, 26f);
    private static final AnimatedIconSpec CAMERA = AnimatedIconSpec.frames(new int[]{R.drawable.outline_header_camera_plus_24, R.drawable.outline_header_camera_plus_24, R.drawable.filled_add_photo, R.drawable.filled_add_photo, R.drawable.outline_header_camera_plus_24, R.drawable.outline_header_camera_plus_24}, new float[]{0f, 10f, 26f, 42f, 61f, 85f}).withPulseFrames(0f, 10f, 26f, 42f, 61f, 85f).withScale(0.4f);
    private static final AnimatedIconSpec CAMERA_OUTLINE = AnimatedIconSpec.frames(new int[]{R.drawable.outline_header_camera_plus_24, R.drawable.outline_header_camera_plus_24, R.drawable.filled_add_photo, R.drawable.filled_add_photo, R.drawable.outline_header_camera_plus_24, R.drawable.outline_header_camera_plus_24}, new float[]{0f, 10f, 26f, 42f, 61f, 85f}).withPulseFrames(0f, 10f, 26f, 42f, 61f, 85f).withScale(0.47f);
    private static final AnimatedIconSpec CAMERA_WAIT = AnimatedIconSpec.pulse(R.drawable.outline_header_camera_plus_24).withPulseFrames(0f, 299f).withScale(0.4f);
    private static final AnimatedIconSpec ROUNDCAMERA_FLASH_ON = AnimatedIconSpec.frames(new int[]{R.drawable.flash_off, R.drawable.flash_on}, new float[]{0f, 12f}).withAlignedTransition();
    private static final AnimatedIconSpec ROUNDCAMERA_FLASH_OFF = AnimatedIconSpec.frames(new int[]{R.drawable.flash_on, R.drawable.flash_off}, new float[]{0f, 11f}).withAlignedTransition();
    private static final AnimatedIconSpec ROUNDCAMERA_FLIP = AnimatedIconSpec.pulse(R.drawable.camera_revert1).withPulseFrames(0f, 29f);
    private static final AnimatedIconSpec GROUP_PIP_DELETE_ICON = AnimatedIconSpec.pulse(R.drawable.msg_delete).withPulseFrames(0f, 32f, 33f, 65f);
    private static final AnimatedIconSpec CHAT_AUDIO_RECORD_DELETE = AnimatedIconSpec.recordDelete(R.drawable.msg_delete, 1).withColorLayer("Cup Red").withScale(.94f);
    private static final AnimatedIconSpec CHAT_AUDIO_RECORD_DELETE_3 = AnimatedIconSpec.recordDelete(R.drawable.msg_delete, 3).withColorLayer("Cup Red").withScale(.94f);
    private static final AnimatedIconSpec CHATS_ARCHIVEAVATAR = AnimatedIconSpec.pulse(R.drawable.chats_archive).withColorLayer("Box1");
    private static final AnimatedIconSpec CHATS_ARCHIVE = AnimatedIconSpec.pulse(R.drawable.chats_archive).withColorLayer("Box1").withMotion(NativeIconMotion.forRaw(R.raw.chats_archive));
    private static final AnimatedIconSpec CHATS_UNARCHIVE = AnimatedIconSpec.pulse(R.drawable.msg_unarchive).withColorLayer("Box1").withMotion(NativeIconMotion.forRaw(R.raw.chats_unarchive));
    private static final AnimatedIconSpec CHATS_HIDE = AnimatedIconSpec.pulse(R.drawable.msg_archive_hide).withColorLayer("Arrow");
    private static final AnimatedIconSpec CHATS_UNHIDE = AnimatedIconSpec.pulse(R.drawable.msg_archive_show).withColorLayer("Arrow");
    private static final AnimatedIconSpec SWIPE_MUTE = AnimatedIconSpec.pulse(R.drawable.input_notify_off).withMotion(NativeIconMotion.forRaw(R.raw.swipe_mute));
    private static final AnimatedIconSpec SWIPE_UNMUTE = AnimatedIconSpec.pulse(R.drawable.input_notify_on).withMotion(NativeIconMotion.forRaw(R.raw.swipe_unmute));
    private static final AnimatedIconSpec SWIPE_READ = AnimatedIconSpec.pulse(R.drawable.msg_markread).withMotion(NativeIconMotion.forRaw(R.raw.swipe_read));
    private static final AnimatedIconSpec SWIPE_UNREAD = AnimatedIconSpec.pulse(R.drawable.msg_markunread).withMotion(NativeIconMotion.forRaw(R.raw.swipe_unread));
    private static final AnimatedIconSpec SWIPE_DELETE = AnimatedIconSpec.pulse(R.drawable.msg_delete).withMotion(NativeIconMotion.forRaw(R.raw.swipe_delete));
    private static final AnimatedIconSpec SWIPE_PIN = AnimatedIconSpec.pulse(R.drawable.msg_pin).withMotion(NativeIconMotion.forRaw(R.raw.swipe_pin));
    private static final AnimatedIconSpec SWIPE_UNPIN = AnimatedIconSpec.pulse(R.drawable.msg_unpin).withMotion(NativeIconMotion.forRaw(R.raw.swipe_unpin));
    private static final AnimatedIconSpec TRANSCRIBE_OUT = AnimatedIconSpec.frames(new int[]{R.drawable.msg_contacts_name, R.drawable.msg_go_up}, new float[]{0f, 19f});
    private static final AnimatedIconSpec TRANSCRIBE_IN = AnimatedIconSpec.frames(new int[]{R.drawable.msg_go_up, R.drawable.msg_contacts_name}, new float[]{0f, 19f});
    private static final AnimatedIconSpec PHOTO_TEXT_ALLIGN = AnimatedIconSpec.frames(new int[]{R.drawable.iv_align_horiz_middle, R.drawable.iv_align_horiz_left, R.drawable.iv_align_horiz_left, R.drawable.iv_align_horiz_right, R.drawable.iv_align_horiz_right, R.drawable.iv_align_horiz_middle, R.drawable.iv_align_horiz_middle}, new float[]{0f, 19f, 20f, 39f, 40f, 59f, 60f}).withAlignedTransition();
    private static final AnimatedIconSpec POSITION_ABOVE = AnimatedIconSpec.frames(new int[]{R.drawable.menu_link_above, R.drawable.menu_link_below}, new float[]{0f, 12f}).withAlignedTransition();
    private static final AnimatedIconSpec POSITION_BELOW = AnimatedIconSpec.frames(new int[]{R.drawable.menu_link_below, R.drawable.menu_link_above}, new float[]{0f, 12f}).withAlignedTransition();
    private static final AnimatedIconSpec CAPTION_UP = AnimatedIconSpec.pulse(R.drawable.menu_link_below);
    private static final AnimatedIconSpec CAPTION_DOWN = AnimatedIconSpec.pulse(R.drawable.menu_link_above);
    private static final AnimatedIconSpec NAME_HIDE = AnimatedIconSpec.frames(new int[]{R.drawable.msg_contacts, AnimatedIconSpec.slashed(R.drawable.msg_contacts)}, new float[]{0f, 19f});
    private static final AnimatedIconSpec NAME_SHOW = AnimatedIconSpec.frames(new int[]{AnimatedIconSpec.slashed(R.drawable.msg_contacts), R.drawable.msg_contacts}, new float[]{0f, 19f});
    private static final AnimatedIconSpec CAPTION_HIDE = AnimatedIconSpec.frames(new int[]{R.drawable.msg_stories_caption, AnimatedIconSpec.slashed(R.drawable.msg_stories_caption)}, new float[]{0f, 19f});
    private static final AnimatedIconSpec CAPTION_SHOW = AnimatedIconSpec.frames(new int[]{AnimatedIconSpec.slashed(R.drawable.msg_stories_caption), R.drawable.msg_stories_caption}, new float[]{0f, 19f});
    private static final AnimatedIconSpec PHOTO_SPOILER = AnimatedIconSpec.pulse(R.drawable.msg_spoiler);
    private static final AnimatedIconSpec CONTACT_CHECK = AnimatedIconSpec.pulse(R.drawable.ic_ab_done).withColorLayer("Oval").withMotion(NativeIconMotion.forRaw(R.raw.contact_check));
    private static final AnimatedIconSpec ERROR = AnimatedIconSpec.pulse(R.drawable.msg_warning).withColorLayer("error").withMotion(NativeIconMotion.forRaw(R.raw.error));
    private static final AnimatedIconSpec INFO = AnimatedIconSpec.pulse(R.drawable.msg_info).withColorLayer("Oval").withMotion(NativeIconMotion.forRaw(R.raw.info));
    private static final AnimatedIconSpec CHATS_INFOTIP = AnimatedIconSpec.pulse(R.drawable.msg_info).withColorLayer("Oval").withMotion(NativeIconMotion.forRaw(R.raw.chats_infotip));
    private static final AnimatedIconSpec IC_DELETE = AnimatedIconSpec.pulse(R.drawable.msg_delete).withColorLayer("Bucket");
    private static final AnimatedIconSpec COPY = AnimatedIconSpec.pulse(R.drawable.msg_copy).withColorLayer("Front").withMotion(NativeIconMotion.forRaw(R.raw.copy));
    private static final AnimatedIconSpec FORWARD = AnimatedIconSpec.pulse(R.drawable.msg_forward).withColorLayer("Circle Main 2").withMotion(NativeIconMotion.forRaw(R.raw.forward));
    private static final AnimatedIconSpec SAVED_MESSAGES = AnimatedIconSpec.pulse(R.drawable.msg_saved).withColorLayer("Round");
    private static final AnimatedIconSpec SILENT_MUTE = AnimatedIconSpec.pulse(R.drawable.input_notify_off).withColorLayer("BODY").withMotion(NativeIconMotion.forRaw(R.raw.silent_mute));
    private static final AnimatedIconSpec SILENT_UNMUTE = AnimatedIconSpec.pulse(R.drawable.input_notify_on).withColorLayer("BODY").withMotion(NativeIconMotion.forRaw(R.raw.silent_unmute));
    private static final AnimatedIconSpec IC_MUTE = AnimatedIconSpec.pulse(R.drawable.msg_mute).withColorLayer("Body Main");
    private static final AnimatedIconSpec IC_UNMUTE = AnimatedIconSpec.pulse(R.drawable.msg_unmute).withColorLayer("BODY");
    private static final AnimatedIconSpec IC_PIN = AnimatedIconSpec.pulse(R.drawable.msg_pin).withColorLayer("Pin");
    private static final AnimatedIconSpec IC_UNPIN = AnimatedIconSpec.pulse(R.drawable.msg_unpin).withColorLayer("Pin");
    private static final AnimatedIconSpec LINKBROKEN = AnimatedIconSpec.pulse(R.drawable.menu_link_revoke).withColorLayer("top");
    private static final AnimatedIconSpec IC_DOWNLOAD = AnimatedIconSpec.pulse(R.drawable.msg_download).withColorLayer("Arrow");
    private static final AnimatedIconSpec MSG_TRANSLATE = AnimatedIconSpec.pulse(R.drawable.msg_translate).withColorLayer("ee Outlines");
    private static final AnimatedIconSpec TIMER_TOAST = AnimatedIconSpec.pulse(R.drawable.msg_autodelete).withColorLayer("Body");
    private static final AnimatedIconSpec TICKS_SINGLE = AnimatedIconSpec.pulse(R.drawable.msg_halfcheck).withColorLayer("Half");
    private static final AnimatedIconSpec TICKS_DOUBLE = AnimatedIconSpec.pulse(R.drawable.msg_mini_checks).withColorLayer("Check 2");
    private static final AnimatedIconSpec IC_SAVE_TO_GALLERY = AnimatedIconSpec.pulse(R.drawable.msg_gallery).withColorLayer("Arrow");
    private static final AnimatedIconSpec IC_SAVE_TO_MUSIC = AnimatedIconSpec.pulse(R.drawable.msg_download).withColorLayer("Arrow");
    private static final AnimatedIconSpec STORY_REPOST = AnimatedIconSpec.pulse(R.drawable.media_repost).withColorLayer("Big Path 3");
    private static final AnimatedIconSpec DOWNLOAD_PROGRESS = AnimatedIconSpec.pulse(R.drawable.ng_header_download).withPulseFrames(0f, 49f);
    private static final AnimatedIconSpec DOWNLOAD_FINISH = AnimatedIconSpec.pulse(R.drawable.ng_header_download).withPulseFrames(0f, 40f);
    private static final AnimatedIconSpec MEDIA_SHRINK = AnimatedIconSpec.frames(new int[]{R.drawable.voice_minimize, R.drawable.voice_expand}, new float[]{2f, 10f}).withAlignedTransition();
    private static final AnimatedIconSpec MEDIA_ENLARGE = AnimatedIconSpec.frames(new int[]{R.drawable.voice_expand, R.drawable.voice_minimize}, new float[]{0f, 10f}).withAlignedTransition();
    private static final AnimatedIconSpec BOOSTS = AnimatedIconSpec.pulse(R.drawable.large_boosts).withPulseFrames(0f, 24f, 25f, 48f);
    private static final AnimatedIconSpec CHAT_AUDIO_RECORD_DELETE_2 = AnimatedIconSpec.recordDelete(R.drawable.msg_delete, 2).withColorTransition("Cup Grey", "Cup Red", 14f).withScale(.94f);
    private static final AnimatedIconSpec PHOTO_SUGGEST_ICON = AnimatedIconSpec.frames(new int[]{R.drawable.msg_addphoto, R.drawable.msg_addphoto, R.drawable.filled_add_photo, R.drawable.filled_add_photo, R.drawable.filled_add_photo, R.drawable.msg_addphoto, R.drawable.msg_addphoto}, new float[]{0f, 2f, 25f, 42f, 43f, 69f, 84f}).withPulseFrames(0f, 2f, 25f, 42f, 69f, 84f).withScale(0.46f);
    private static final AnimatedIconSpec PASSCODE_LOCK_CLOSE = AnimatedIconSpec.frames(new int[]{R.drawable.menu_unlock, R.drawable.ic_lock_white, R.drawable.ic_lock_white}, new float[]{0f, 32f, 38f}).withPulseFrames(0f, 32f, 33f, 38f).withScale(0.75f).withAlignedTransition();
    private static final AnimatedIconSpec SWIPE_DISABLED = AnimatedIconSpec.pulse(R.drawable.msg_disable).withPulseFrames(0f, 29f).withMotion(NativeIconMotion.forRaw(R.raw.swipe_disabled));
    private static final AnimatedIconSpec FIRE_ON = AnimatedIconSpec.pulse(R.drawable.filled_fire).withColorLayer("fire").withPulseFrames(0f, 119f).withScale(.85f);
    private static final AnimatedIconSpec FIRE_OFF = AnimatedIconSpec.frames(new int[]{R.drawable.filled_fire, AnimatedIconSpec.slashed(R.drawable.filled_fire), AnimatedIconSpec.slashed(R.drawable.filled_fire)}, new float[]{0f, 19f, 119f}).withColorLayer("fire").withPulseFrames(0f, 18f, 19f, 119f).withAlignedTransition().withScale(.7f);

    public static AnimatedIconSpec get(int raw) {
        if (raw == R.raw.msg_emoji_smiles) return MSG_EMOJI_SMILES;
        if (raw == R.raw.msg_emoji_cat) return MSG_EMOJI_CAT;
        if (raw == R.raw.msg_emoji_food) return MSG_EMOJI_FOOD;
        if (raw == R.raw.msg_emoji_activities) return MSG_EMOJI_ACTIVITIES;
        if (raw == R.raw.msg_emoji_travel) return MSG_EMOJI_TRAVEL;
        if (raw == R.raw.msg_emoji_objects) return MSG_EMOJI_OBJECTS;
        if (raw == R.raw.msg_emoji_other) return MSG_EMOJI_OTHER;
        if (raw == R.raw.msg_emoji_flags) return MSG_EMOJI_FLAGS;
        if (raw == R.raw.player_prev) return PLAYER_PREV;
        if (raw == R.raw.write_contacts_fab_icon) return WRITE_CONTACTS_FAB_ICON;
        if (raw == R.raw.passcode_lock) return PASSCODE_LOCK;
        if (raw == R.raw.unlock_icon) return UNLOCK_ICON;
        if (raw == R.raw.notify_toggle) return NOTIFY_TOGGLE;
        if (raw == R.raw.sun) return SUN;
        if (raw == R.raw.sun_outline) return SUN_OUTLINE;
        if (raw == R.raw.media_mute_unmute) return MEDIA_MUTE_UNMUTE;
        if (raw == R.raw.download_arrow) return DOWNLOAD_ARROW;
        if (raw == R.raw.bot_webview_sheet_to_cross) return BOT_WEBVIEW_SHEET_TO_CROSS;
        if (raw == R.raw.msg_stories_saved) return MSG_STORIES_SAVED;
        if (raw == R.raw.msg_stories_archive) return MSG_STORIES_ARCHIVE;
        if (raw == R.raw.camera) return CAMERA;
        if (raw == R.raw.camera_outline) return CAMERA_OUTLINE;
        if (raw == R.raw.camera_wait) return CAMERA_WAIT;
        if (raw == R.raw.roundcamera_flash_on) return ROUNDCAMERA_FLASH_ON;
        if (raw == R.raw.roundcamera_flash_off) return ROUNDCAMERA_FLASH_OFF;
        if (raw == R.raw.roundcamera_flip) return ROUNDCAMERA_FLIP;
        if (raw == R.raw.group_pip_delete_icon) return GROUP_PIP_DELETE_ICON;
        if (raw == R.raw.chat_audio_record_delete) return CHAT_AUDIO_RECORD_DELETE;
        if (raw == R.raw.chat_audio_record_delete_3) return CHAT_AUDIO_RECORD_DELETE_3;
        if (raw == R.raw.chats_archiveavatar) return CHATS_ARCHIVEAVATAR;
        if (raw == R.raw.chats_archive) return CHATS_ARCHIVE;
        if (raw == R.raw.chats_unarchive) return CHATS_UNARCHIVE;
        if (raw == R.raw.chats_hide) return CHATS_HIDE;
        if (raw == R.raw.chats_unhide) return CHATS_UNHIDE;
        if (raw == R.raw.swipe_mute) return SWIPE_MUTE;
        if (raw == R.raw.swipe_unmute) return SWIPE_UNMUTE;
        if (raw == R.raw.swipe_read) return SWIPE_READ;
        if (raw == R.raw.swipe_unread) return SWIPE_UNREAD;
        if (raw == R.raw.swipe_delete) return SWIPE_DELETE;
        if (raw == R.raw.swipe_pin) return SWIPE_PIN;
        if (raw == R.raw.swipe_unpin) return SWIPE_UNPIN;
        if (raw == R.raw.transcribe_out) return TRANSCRIBE_OUT;
        if (raw == R.raw.transcribe_in) return TRANSCRIBE_IN;
        if (raw == R.raw.photo_text_allign) return PHOTO_TEXT_ALLIGN;
        if (raw == R.raw.position_above) return POSITION_ABOVE;
        if (raw == R.raw.position_below) return POSITION_BELOW;
        if (raw == R.raw.caption_up) return CAPTION_UP;
        if (raw == R.raw.caption_down) return CAPTION_DOWN;
        if (raw == R.raw.name_hide) return NAME_HIDE;
        if (raw == R.raw.name_show) return NAME_SHOW;
        if (raw == R.raw.caption_hide) return CAPTION_HIDE;
        if (raw == R.raw.caption_show) return CAPTION_SHOW;
        if (raw == R.raw.photo_spoiler) return PHOTO_SPOILER;
        if (raw == R.raw.contact_check) return CONTACT_CHECK;
        if (raw == R.raw.error) return ERROR;
        if (raw == R.raw.info) return INFO;
        if (raw == R.raw.chats_infotip) return CHATS_INFOTIP;
        if (raw == R.raw.ic_delete) return IC_DELETE;
        if (raw == R.raw.copy) return COPY;
        if (raw == R.raw.forward) return FORWARD;
        if (raw == R.raw.saved_messages) return SAVED_MESSAGES;
        if (raw == R.raw.silent_mute) return SILENT_MUTE;
        if (raw == R.raw.silent_unmute) return SILENT_UNMUTE;
        if (raw == R.raw.ic_mute) return IC_MUTE;
        if (raw == R.raw.ic_unmute) return IC_UNMUTE;
        if (raw == R.raw.ic_pin) return IC_PIN;
        if (raw == R.raw.ic_unpin) return IC_UNPIN;
        if (raw == R.raw.linkbroken) return LINKBROKEN;
        if (raw == R.raw.ic_download) return IC_DOWNLOAD;
        if (raw == R.raw.msg_translate) return MSG_TRANSLATE;
        if (raw == R.raw.timer_toast) return TIMER_TOAST;
        if (raw == R.raw.ticks_single) return TICKS_SINGLE;
        if (raw == R.raw.ticks_double) return TICKS_DOUBLE;
        if (raw == R.raw.ic_save_to_gallery) return IC_SAVE_TO_GALLERY;
        if (raw == R.raw.ic_save_to_music) return IC_SAVE_TO_MUSIC;
        if (raw == R.raw.story_repost) return STORY_REPOST;
        if (raw == R.raw.download_progress) return DOWNLOAD_PROGRESS;
        if (raw == R.raw.download_finish) return DOWNLOAD_FINISH;
        if (raw == R.raw.media_shrink) return MEDIA_SHRINK;
        if (raw == R.raw.media_enlarge) return MEDIA_ENLARGE;
        if (raw == R.raw.boosts) return BOOSTS;
        if (raw == R.raw.chat_audio_record_delete_2) return CHAT_AUDIO_RECORD_DELETE_2;
        if (raw == R.raw.photo_suggest_icon) return PHOTO_SUGGEST_ICON;
        if (raw == R.raw.passcode_lock_close) return PASSCODE_LOCK_CLOSE;
        if (raw == R.raw.swipe_disabled) return SWIPE_DISABLED;
        if (raw == R.raw.fire_on) return FIRE_ON;
        if (raw == R.raw.fire_off) return FIRE_OFF;
        return null;
    }

    public static int[] drawableIds() {
        return new int[] {
                R.drawable.bot_webview,
                R.drawable.camera_revert1,
                R.drawable.chats_archive,
                R.drawable.filled_add_photo,
                R.drawable.filled_fire,
                R.drawable.filled_new_contact_24,
                R.drawable.flash_off,
                R.drawable.flash_on,
                R.drawable.ic_ab_done,
                R.drawable.ic_action_previous,
                R.drawable.ic_close_white,
                R.drawable.ic_lock_white,
                R.drawable.input_notify_off,
                R.drawable.input_notify_on,
                R.drawable.iv_align_horiz_left,
                R.drawable.iv_align_horiz_middle,
                R.drawable.iv_align_horiz_right,
                R.drawable.large_boosts,
                R.drawable.media_mute,
                R.drawable.media_repost,
                R.drawable.media_unmute,
                R.drawable.menu_day_mode_24,
                R.drawable.menu_link_above,
                R.drawable.menu_link_below,
                R.drawable.menu_link_revoke,
                R.drawable.menu_night_mode_24,
                R.drawable.menu_unlock,
                R.drawable.msg_addphoto,
                R.drawable.msg_archive_hide,
                R.drawable.msg_archive_show,
                R.drawable.msg_autodelete,
                R.drawable.msg_contacts,
                R.drawable.msg_contacts_name,
                R.drawable.msg_copy,
                R.drawable.msg_delete,
                R.drawable.msg_disable,
                R.drawable.msg_download,
                R.drawable.ng_file_download,
                R.drawable.ng_header_download,
                R.drawable.msg_edit,
                R.drawable.msg_emoji_activities,
                R.drawable.msg_emoji_cat,
                R.drawable.msg_emoji_flags,
                R.drawable.msg_emoji_food,
                R.drawable.msg_emoji_objects,
                R.drawable.msg_emoji_other,
                R.drawable.msg_emoji_smiles,
                R.drawable.msg_emoji_travel,
                R.drawable.msg_forward,
                R.drawable.msg_gallery,
                R.drawable.msg_go_up,
                R.drawable.msg_halfcheck,
                R.drawable.msg_info,
                R.drawable.msg_markread,
                R.drawable.msg_markunread,
                R.drawable.msg_mini_checks,
                R.drawable.msg_mute,
                R.drawable.msg_pin,
                R.drawable.msg_saved,
                R.drawable.msg_spoiler,
                R.drawable.msg_stories_archive,
                R.drawable.msg_stories_caption,
                R.drawable.msg_stories_saved,
                R.drawable.msg_translate,
                R.drawable.msg_unarchive,
                R.drawable.msg_unmute,
                R.drawable.msg_unpin,
                R.drawable.msg_warning,
                R.drawable.outline_header_camera_plus_24,
                R.drawable.voice_expand,
                R.drawable.voice_minimize
        };
    }
}
