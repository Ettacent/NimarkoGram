package app.nimarkogram.messenger.components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.text.Editable;
import android.text.Selection;
import android.view.DragEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.core.view.ContentInfoCompat;
import androidx.core.view.OnReceiveContentViewBehavior;
import androidx.core.view.ViewCompat;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.widget.TextViewOnReceiveContentListener;

@SuppressLint({"RestrictedApi", "AppCompatCustomView"})
public abstract class ReceiveContentEditText extends EditText implements OnReceiveContentViewBehavior {

    private final TextViewOnReceiveContentListener defaultOnReceiveContentListener;

    public ReceiveContentEditText(Context context) {
        super(context);
        this.defaultOnReceiveContentListener = new TextViewOnReceiveContentListener();
    }

    private Activity findActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private boolean handleDragEventViaReceiveContent(DragEvent dragEvent) {
        if (Build.VERSION.SDK_INT >= 31
                || dragEvent.getLocalState() != null
                || ViewCompat.getOnReceiveContentMimeTypes(this) == null) {
            return false;
        }
        Activity activity = findActivity();
        if (activity == null
                || dragEvent.getAction() == DragEvent.ACTION_DRAG_STARTED
                || dragEvent.getAction() != DragEvent.ACTION_DROP) {
            return false;
        }
        return OnDropApi24Impl.onDropForTextView(dragEvent, this, activity);
    }

    private boolean handleMenuActionViaReceiveContent(int menuItemId) {
        if (ViewCompat.getOnReceiveContentMimeTypes(this) == null
                || !(menuItemId == android.R.id.paste || menuItemId == android.R.id.pasteAsPlainText)) {
            return false;
        }
        ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData primaryClip = clipboardManager == null ? null : clipboardManager.getPrimaryClip();
        if (primaryClip != null && primaryClip.getItemCount() > 0) {
            int flags = menuItemId == android.R.id.paste ? 0 : ContentInfoCompat.FLAG_CONVERT_TO_PLAIN_TEXT;
            ViewCompat.performReceiveContent(this,
                    new ContentInfoCompat.Builder(primaryClip, ContentInfoCompat.SOURCE_CLIPBOARD)
                            .setFlags(flags)
                            .build());
        }
        return true;
    }

    @Override
    public Editable getText() {
        return Build.VERSION.SDK_INT >= 28 ? super.getText() : super.getEditableText();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        InputConnection inputConnection = super.onCreateInputConnection(editorInfo);
        if (inputConnection == null || Build.VERSION.SDK_INT > 30) {
            return inputConnection;
        }
        String[] mimeTypes = ViewCompat.getOnReceiveContentMimeTypes(this);
        if (mimeTypes == null) {
            return inputConnection;
        }
        EditorInfoCompat.setContentMimeTypes(editorInfo, mimeTypes);
        return InputConnectionCompat.createWrapper(this, inputConnection, editorInfo);
    }

    @Override
    public boolean onDragEvent(DragEvent dragEvent) {
        if (handleDragEventViaReceiveContent(dragEvent)) {
            return true;
        }
        return super.onDragEvent(dragEvent);
    }

    @Override
    public ContentInfoCompat onReceiveContent(ContentInfoCompat contentInfoCompat) {
        return this.defaultOnReceiveContentListener.onReceiveContent(this, contentInfoCompat);
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (handleMenuActionViaReceiveContent(id)) {
            return true;
        }
        return super.onTextContextMenuItem(id);
    }

    static final class OnDropApi24Impl {
        static boolean onDropForTextView(DragEvent dragEvent, ReceiveContentEditText view, Activity activity) {
            activity.requestDragAndDropPermissions(dragEvent);
            int offset = view.getOffsetForPosition(dragEvent.getX(), dragEvent.getY());
            view.beginBatchEdit();
            try {
                Selection.setSelection(view.getText(), offset);
                ViewCompat.performReceiveContent(view,
                        new ContentInfoCompat.Builder(dragEvent.getClipData(), ContentInfoCompat.SOURCE_DRAG_AND_DROP).build());
            } finally {
                view.endBatchEdit();
            }
            return true;
        }
    }
}
