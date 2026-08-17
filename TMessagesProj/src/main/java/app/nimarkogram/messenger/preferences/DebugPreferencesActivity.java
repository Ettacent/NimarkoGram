/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */
package app.nimarkogram.messenger.preferences;

import android.os.Build;
import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class DebugPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_SHOW_RPC_ERRORS    = 1;
    private static final int ID_AUDIO_SOURCE       = 2;
    private static final int ID_JACKSON_JSON       = 3;
    private static final int ID_PLAY_GIFS_AS_VIDEO = 4;
    private static final int ID_HIDE_VIDEO_TS      = 5;
    private static final int ID_OLD_TIME_STYLE     = 6;
    private static final int ID_REPLACE_PUNCT      = 7;
    private static final int ID_EDIT_TEXT_FIX      = 8;
    private static final int ID_SHOW_ACCOUNTS      = 9;
    private static final int ID_SEND_MAX_QUALITY   = 11;
    private static final int ID_FORCE_CRASH        = 10;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_DBG_Title);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_DBG_Header_Misc)));
        items.add(UItem.asCheck(ID_SHOW_RPC_ERRORS, LocaleController.getString(R.string.NM_DBG_ShowRPCErrors))
                .setChecked(NimarkoConfig.showRPCErrors));
        items.add(UItem.asCheck(ID_OLD_TIME_STYLE, LocaleController.getString(R.string.NM_DBG_OldTimeStyle))
                .setChecked(NimarkoConfig.oldTimeStyle));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.add(UItem.asButton(ID_AUDIO_SOURCE,
                    LocaleController.getString(R.string.NM_DBG_AudioSource),
                    audioSourceLabel(NimarkoConfig.audioSource)));
        }
        items.add(UItem.asCheck(ID_JACKSON_JSON, LocaleController.getString(R.string.NM_DBG_JacksonJSONProvider))
                .setChecked(NimarkoConfig.jacksonJSON_Provider));
        items.add(UItem.asCheck(ID_PLAY_GIFS_AS_VIDEO, LocaleController.getString(R.string.NM_DBG_PlayGIFsAsVideos))
                .setChecked(NimarkoConfig.playGIFsAsVideos));
        items.add(UItem.asCheck(ID_HIDE_VIDEO_TS, LocaleController.getString(R.string.NM_DBG_HideVideoTimestamp))
                .setChecked(NimarkoConfig.hideVideoTimestamp));
        items.add(UItem.asCheck(ID_REPLACE_PUNCT, LocaleController.getString(R.string.NM_DBG_ReplacePunctuation))
                .setChecked(NimarkoConfig.replacePunctuationMarks));
        items.add(UItem.asCheck(ID_EDIT_TEXT_FIX, LocaleController.getString(R.string.NM_DBG_EditTextFix))
                .setChecked(NimarkoConfig.editTextSuggestionsFix));
        items.add(UItem.asCheck(ID_SHOW_ACCOUNTS, LocaleController.getString(R.string.NM_DBG_ShowAccounts))
                .setChecked(NimarkoConfig.showAccounts));
        
        items.add(UItem.asCheck(ID_SEND_MAX_QUALITY, LocaleController.getString(R.string.NM_DBG_SendMaxQuality))
                .setChecked(NimarkoConfig.sendVideosAtMaxQuality));
        items.add(UItem.asShadow(null));

        if (BuildVars.DEBUG_VERSION) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_DBG_Header_Danger)));
            items.add(UItem.asButton(ID_FORCE_CRASH, LocaleController.getString(R.string.NM_DBG_ForceCrash)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.NM_DBG_ForceCrash_Desc)));
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_SHOW_RPC_ERRORS) {
            NimarkoConfig.toggleShowRPCErrors();
            applyCheck(item, view, NimarkoConfig.showRPCErrors);
            showRestartBulletin();
        } else if (id == ID_OLD_TIME_STYLE) {
            NimarkoConfig.toggleOldTimeStyle();
            applyCheck(item, view, NimarkoConfig.oldTimeStyle);
        } else if (id == ID_AUDIO_SOURCE) {
            PopupHelper.showSimpleAlert(this,
                    LocaleController.getString(R.string.NM_DBG_AudioSource),
                    audioSourceOptions(), audioSourceSelection(NimarkoConfig.audioSource), sel -> {
                        int[] values = audioSourceValues();
                        int source = sel >= 0 && sel < values.length
                                ? values[sel] : NimarkoConfig.AUDIO_SOURCE_DEFAULT;
                        NimarkoConfig.setAudioSource(source);
                        if (view instanceof TextCell) {
                            ((TextCell) view).setValue(audioSourceLabel(source), true);
                        }
                        if (listView != null && listView.adapter != null) listView.adapter.update(true);
                    });
        } else if (id == ID_JACKSON_JSON) {
            NimarkoConfig.toggleJacksonJSON_Provider();
            applyCheck(item, view, NimarkoConfig.jacksonJSON_Provider);
            showRestartBulletin();
        } else if (id == ID_PLAY_GIFS_AS_VIDEO) {
            NimarkoConfig.togglePlayGIFsAsVideos();
            applyCheck(item, view, NimarkoConfig.playGIFsAsVideos);
        } else if (id == ID_HIDE_VIDEO_TS) {
            NimarkoConfig.toggleHideVideoTimestamp();
            applyCheck(item, view, NimarkoConfig.hideVideoTimestamp);
        } else if (id == ID_REPLACE_PUNCT) {
            NimarkoConfig.toggleReplacePunctuationMarks();
            applyCheck(item, view, NimarkoConfig.replacePunctuationMarks);
            showRestartBulletin();
        } else if (id == ID_EDIT_TEXT_FIX) {
            NimarkoConfig.toggleEditTextSuggestionsFix();
            applyCheck(item, view, NimarkoConfig.editTextSuggestionsFix);
            showRestartBulletin();
        } else if (id == ID_SHOW_ACCOUNTS) {
            NimarkoConfig.toggleShowAccounts();
            applyCheck(item, view, NimarkoConfig.showAccounts);
            showRestartBulletin();
        } else if (id == ID_SEND_MAX_QUALITY) {
            NimarkoConfig.toggleSendVideosAtMaxQuality();
            applyCheck(item, view, NimarkoConfig.sendVideosAtMaxQuality);
        } else if (id == ID_FORCE_CRASH && BuildVars.DEBUG_VERSION) {
            
            throw new RuntimeException("NimarkoGram debug: force-crash button");
        }
    }

    private String[] audioSourceOptions() {
        int[] values = audioSourceValues();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = audioSourceLabel(values[i]);
        return labels;
    }

    private int[] audioSourceValues() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new int[]{0, 1, 5, 6, 7, 9, 10};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new int[]{0, 1, 5, 6, 7, 9};
        }
        return new int[]{0, 1, 5, 6, 7};
    }

    private int audioSourceSelection(int value) {
        int sanitized = NimarkoConfig.sanitizeAudioSource(value);
        int[] values = audioSourceValues();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == sanitized) return i;
        }
        return 0;
    }

    private String audioSourceLabel(int v) {
        switch (NimarkoConfig.sanitizeAudioSource(v)) {
            case NimarkoConfig.AUDIO_SOURCE_MIC: return "MIC";
            case NimarkoConfig.AUDIO_SOURCE_CAMCORDER: return "CAMCORDER";
            case NimarkoConfig.AUDIO_SOURCE_VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case NimarkoConfig.AUDIO_SOURCE_VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case NimarkoConfig.AUDIO_SOURCE_UNPROCESSED: return "UNPROCESSED";
            case NimarkoConfig.AUDIO_SOURCE_VOICE_PERFORMANCE: return "VOICE_PERFORMANCE";
            default: return "DEFAULT";
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }
}
