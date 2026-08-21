package app.nimarkogram.messenger.preferences;

import android.content.Context;
import android.view.View;

import androidx.core.view.ViewCompat;

import org.telegram.ui.Components.UniversalFragment;

public abstract class NimarkoUniversalPreferencesActivity extends UniversalFragment {

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ViewCompat.setOnApplyWindowInsetsListener(view, this::onInsetsInternal);
        ViewCompat.requestApplyInsets(view);
        return view;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }
}
