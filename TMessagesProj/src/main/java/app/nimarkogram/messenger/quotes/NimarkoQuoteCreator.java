package app.nimarkogram.messenger.quotes;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Spanned;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MessagePreviewView;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.Paint.Views.EntityView;
import org.telegram.ui.Components.Paint.Views.MessageEntityView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import app.nimarkogram.messenger.NimarkoMessageMenuInjector;

public final class NimarkoQuoteCreator {

    public static final int MAX_MESSAGES = 20;
    private static final long MAX_EXPORT_PIXELS = 6_000_000L;
    private static final long LOW_MEMORY_EXPORT_PIXELS = 3_000_000L;
    private static final int MAX_EXPORT_SIDE = 8_192;
    private static final long MAX_LIVE_PREVIEW_PIXELS = 1_600_000L;
    private static final long MAX_SNAPSHOT_PREVIEW_PIXELS = 2_000_000L;
    private static final int MAX_SNAPSHOT_PREVIEW_SIDE = 4_096;
    private static final float MAX_INLINE_PHOTO_ASPECT_RATIO = 3f;
    private static final long PREVIEW_REVEAL_DURATION = 300L;
    private static final float PREVIEW_REVEAL_SCALE = 0.996f;
    private static final long MEDIA_PREVIEW_SETTLE_TIMEOUT_MS = 500L;
    private static final long MEDIA_EXPORT_SETTLE_TIMEOUT_MS = 1_200L;
    private static final int MEDIA_PREVIEW_SETTLE_MIN_FRAMES = 2;
    private static final long CACHE_LIFETIME_MS = 24L * 60L * 60L * 1000L;
    private static final DispatchQueue EXPORT_QUEUE = new DispatchQueue(
            "quoteExportQueue",
            true,
            Process.THREAD_PRIORITY_BACKGROUND
    );
    private static final int LEGACY_SAVE_PERMISSION_REQUEST = 7412;
    private static final Object LEGACY_SAVE_LOCK = new Object();
    private static PendingLegacySave pendingLegacySave;

    private NimarkoQuoteCreator() {
    }

    public static boolean onRequestPermissionsResult(
            ChatActivity chatActivity,
            int requestCode,
            int[] grantResults
    ) {
        if (requestCode != LEGACY_SAVE_PERMISSION_REQUEST) return false;
        PendingLegacySave pending;
        synchronized (LEGACY_SAVE_LOCK) {
            pending = pendingLegacySave;
            pendingLegacySave = null;
        }
        if (pending == null) return true;

        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        QuoteSheet quoteSheet = pending.quoteSheet.get();
        if (!granted) {
            if (quoteSheet != null) quoteSheet.saving = false;
            showError(chatActivity, R.string.NM_QC_Failed);
            return true;
        }
        if (quoteSheet != null && !quoteSheet.dismissed) {
            quoteSheet.saveToGallery(pending.file);
        } else {
            saveQuoteToGallery(pending.file, uri -> {
                if (uri != null) {
                    showSaved(chatActivity);
                } else {
                    showError(chatActivity, R.string.NM_QC_Failed);
                }
            });
        }
        return true;
    }

    public static boolean canCreate(
            ChatActivity chatActivity,
            MessageObject selectedObject,
            MessageObject.GroupedMessages selectedObjectGroup
    ) {
        if (!isChatEligible(chatActivity)) return false;
        ArrayList<MessageObject> messages = new ArrayList<>();
        if (selectedObjectGroup != null && selectedObjectGroup.messages != null) {
            messages.addAll(selectedObjectGroup.messages);
        } else if (selectedObject != null) {
            messages.add(selectedObject);
        }
        return !messages.isEmpty() && messages.size() <= MAX_MESSAGES && areMessagesEligible(messages);
    }

    public static void open(
            ChatActivity chatActivity,
            MessageObject selectedObject,
            MessageObject.GroupedMessages selectedObjectGroup
    ) {
        ArrayList<MessageObject> messages = new ArrayList<>();
        if (selectedObjectGroup != null && selectedObjectGroup.messages != null) {
            messages.addAll(selectedObjectGroup.messages);
        } else if (selectedObject != null) {
            messages.add(selectedObject);
        }
        open(chatActivity, messages);
    }

    public static void openSelection(ChatActivity chatActivity) {
        ArrayList<MessageObject> messages = collectSelection(chatActivity);
        AndroidUtilities.runOnUIThread(() -> {
            chatActivity.clearSelectionMode(true, () -> open(chatActivity, messages));
        });
    }

    public static void updateActionModeVisibility(ActionBarMenu actionMode, ChatActivity chatActivity) {
        if (actionMode == null) return;
        ArrayList<MessageObject> messages = collectSelection(chatActivity);
        boolean visible = isChatEligible(chatActivity)
                && !messages.isEmpty()
                && messages.size() <= MAX_MESSAGES
                && areMessagesEligible(messages);
        actionMode.setItemVisibility(
                NimarkoMessageMenuInjector.OPTION_CREATE_QUOTE,
                visible ? View.VISIBLE : View.GONE
        );
    }

    private static boolean open(ChatActivity chatActivity, ArrayList<MessageObject> source) {
        if (!isChatEligible(chatActivity) || source == null || source.isEmpty()) {
            showError(chatActivity, R.string.NM_QC_Protected);
            return false;
        }

        ArrayList<MessageObject> messages = normalize(source);
        if (messages.isEmpty()) {
            showError(chatActivity, R.string.NM_QC_Protected);
            return false;
        }
        if (messages.size() > MAX_MESSAGES) {
            showError(chatActivity, LocaleController.formatString(R.string.NM_QC_SelectLimit, MAX_MESSAGES));
            return false;
        }
        if (!areMessagesEligible(messages)) {
            showError(chatActivity, R.string.NM_QC_Protected);
            return false;
        }

        Activity activity = chatActivity.getParentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        cleanupCache();
        QuoteSheet quoteSheet = new QuoteSheet(chatActivity, messages);
        return quoteSheet.show();
    }

    private static boolean isChatEligible(ChatActivity chatActivity) {
        return chatActivity != null
                && !chatActivity.isSecretChat()
                && !chatActivity.isPeerNoForwards()
                && !chatActivity.isReport();
    }

    private static boolean areMessagesEligible(ArrayList<MessageObject> messages) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null) return false;
            if (message.messageOwner.noforwards
                    || message.messageOwner.media instanceof TLRPC.TL_messageMediaPaidMedia
                    || message.isSponsored()
                    || message.isSecretMedia()
                    || message.isEphemeral()
                    || message.isVoiceOnce()
                    || message.isRoundOnce()
                    || message.isExpiredStory()
                    || message.needDrawBluredPreview()) {
                return false;
            }
        }
        return true;
    }

    private static ArrayList<MessageObject> collectSelection(ChatActivity chatActivity) {
        ArrayList<MessageObject> result = new ArrayList<>();
        if (chatActivity == null) return result;
        for (int index = 0; index < 2; index++) {
            ArrayList<Integer> ids = chatActivity.getSelectedMessagesIds(index);
            for (int i = 0; i < ids.size(); i++) {
                MessageObject message = chatActivity.getSelectedMessage(index, ids.get(i));
                if (message != null) result.add(message);
            }
        }
        return normalize(result);
    }

    private static ArrayList<MessageObject> normalize(ArrayList<MessageObject> source) {
        ArrayList<MessageObject> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            MessageObject message = source.get(i);
            if (message == null || message.messageOwner == null) continue;
            String key = message.getDialogId() + ":" + message.getId();
            if (seen.add(key)) result.add(message);
        }
        Collections.sort(result, (left, right) -> {
            int date = Integer.compare(left.messageOwner.date, right.messageOwner.date);
            if (date != 0) return date;
            int dialog = Long.compare(left.getDialogId(), right.getDialogId());
            if (dialog != 0) return dialog;
            return Integer.compare(left.getId(), right.getId());
        });
        return result;
    }

    private static void showError(ChatActivity chatActivity, int stringId) {
        Activity activity = chatActivity != null ? chatActivity.getParentActivity() : null;
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            BulletinFactory.global()
                    .createSimpleBulletin(R.raw.error, LocaleController.getString(stringId))
                    .show();
        }
    }

    private static void showError(ChatActivity chatActivity, CharSequence text) {
        Activity activity = chatActivity != null ? chatActivity.getParentActivity() : null;
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            BulletinFactory.global().createSimpleBulletin(R.raw.error, text).show();
        }
    }

    private static File getCacheDirectory() {
        Context context = ApplicationLoader.applicationContext;
        File base = context.getExternalCacheDir();
        if (base == null) {
            base = context.getExternalFilesDir(null);
        }
        if (base == null) {
            base = context.getCacheDir();
        }
        return new File(base, "quote_creator");
    }

    private static void cleanupCache() {
        Utilities.globalQueue.postRunnable(() -> {
            File directory = getCacheDirectory();
            File[] files = directory.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - CACHE_LIFETIME_MS;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoff && !file.delete()) {
                    FileLog.d("Unable to remove old quote image " + file.getAbsolutePath());
                }
            }
        });
    }

    private static void scheduleCacheCleanup(File file) {
        if (file == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            if (file.isFile()
                    && System.currentTimeMillis() - file.lastModified() >= CACHE_LIFETIME_MS
                    && !file.delete()) {
                FileLog.d("Unable to remove quote image " + file.getAbsolutePath());
            }
        }, CACHE_LIFETIME_MS);
    }

    private static void saveQuoteToGallery(File source, Utilities.Callback<Uri> callback) {
        EXPORT_QUEUE.postRunnable(() -> {
            Uri result = null;
            if (source != null && source.isFile()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    result = saveQuoteWithMediaStore(source);
                } else {
                    result = saveQuoteToLegacyPictures(source);
                }
            }
            Uri savedUri = result;
            AndroidUtilities.runOnUIThread(() -> callback.run(savedUri));
        });
    }

    private static Uri saveQuoteWithMediaStore(File source) {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + "NimarkoGram"
        );
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Unable to create gallery item");
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Unable to open gallery item");
                copyStream(input, output);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) <= 0) {
                throw new IOException("Unable to publish gallery item");
            }
            return uri;
        } catch (Throwable error) {
            FileLog.e(error);
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable deleteError) {
                    FileLog.e(deleteError);
                }
            }
            return null;
        }
    }

    private static Uri saveQuoteToLegacyPictures(File source) {
        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "NimarkoGram"
        );
        File destination = null;
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Unable to create gallery directory");
            }
            destination = uniqueFile(directory, source.getName());
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(destination)) {
                copyStream(input, output);
            }
            AndroidUtilities.addMediaToGallery(destination);
            return Uri.fromFile(destination);
        } catch (Throwable error) {
            FileLog.e(error);
            if (destination != null && destination.exists()) destination.delete();
            return null;
        }
    }

    private static File uniqueFile(File directory, String fileName) {
        File candidate = new File(directory, fileName);
        if (!candidate.exists()) return candidate;
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; index < 10_000; index++) {
            candidate = new File(directory, name + " (" + index + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, name + "_" + System.currentTimeMillis() + extension);
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static void showSaved(ChatActivity chatActivity) {
        Activity activity = chatActivity != null ? chatActivity.getParentActivity() : null;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        BulletinFactory.global()
                .createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.NM_QC_Saved)
                )
                .show();
    }

    private static final class PendingLegacySave {
        final WeakReference<QuoteSheet> quoteSheet;
        final File file;

        PendingLegacySave(QuoteSheet quoteSheet, File file) {
            this.quoteSheet = new WeakReference<>(quoteSheet);
            this.file = file;
        }
    }

    private enum ExportAction {
        SEND,
        SAVE,
        SHARE
    }

    private static final class QuoteSheet {
        private final ChatActivity chatActivity;
        private final Activity activity;
        private final Theme.ResourcesProvider resourcesProvider;
        private final ArrayList<MessageObject> messages;
        private final LinearLayout rootView;
        private final ScrollView scrollView;
        private final FrameLayout previewFrame;
        private final int maxPreviewHeight;
        private final int reservedPreviewHeight;
        private final ButtonWithCounterView sendButton;
        private final ButtonWithCounterView saveButton;
        private final ButtonWithCounterView shareButton;
        private QuoteCardView quoteCard;
        private QuoteBitmapPreviewView bitmapPreview;
        private Bitmap previewBitmap;
        private BottomSheet sheet;
        private volatile int previewGeneration;
        private int previewBuildStartedGeneration;
        private boolean previewReady;
        private int settledPreviewWidth = -1;
        private int settledPreviewHeight = -1;
        private int stablePreviewLayoutFrames;
        private boolean exporting;
        private boolean saving;
        private volatile boolean dismissed;
        private volatile int exportGeneration;
        private File exportedFile;
        private boolean exportedAsDocument;
        private boolean exportedHasRoundedCorners;

        QuoteSheet(ChatActivity chatActivity, ArrayList<MessageObject> messages) {
            this.chatActivity = chatActivity;
            this.activity = chatActivity.getParentActivity();
            this.resourcesProvider = chatActivity.getResourceProvider();
            this.messages = messages;

            Context context = activity;
            rootView = new LinearLayout(context);
            LinearLayout root = rootView;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
            root.setClipToPadding(false);
            root.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(10));

            View handle = new View(context);
            handle.setBackground(Theme.createRoundRectDrawable(
                    AndroidUtilities.dp(2),
                    Theme.getColor(Theme.key_sheet_scrollUp, resourcesProvider)
            ));
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(36),
                    AndroidUtilities.dp(4)
            );
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.bottomMargin = AndroidUtilities.dp(7);
            root.addView(handle, handleParams);

            TextView title = new TextView(context);
            title.setText(LocaleController.getString(R.string.NM_QC_Preview));
            title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            title.setTextSize(20);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setIncludeFontPadding(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                title.setAccessibilityHeading(true);
            }
            root.addView(title, linear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 10));

            scrollView = new ScrollView(context) {
                private boolean keepTouchForScrolling;

                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                        return;
                    }
                    int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
                    if (availableHeight <= 0) {
                        availableHeight = AndroidUtilities.displaySize.y;
                    }
                    int maxHeight = Math.min(
                            AndroidUtilities.dp(560),
                            Math.round(availableHeight * 0.60f)
                    );
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
                }

                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        keepTouchForScrolling = canScrollVertically(-1);
                    }
                    if (keepTouchForScrolling && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    boolean handled = super.onTouchEvent(event);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        keepTouchForScrolling = false;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                    }
                    return handled;
                }
            };
            scrollView.setClipToPadding(false);
            scrollView.setFillViewport(false);
            scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

            previewFrame = new FrameLayout(context);
            previewFrame.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
            maxPreviewHeight = Math.min(
                    AndroidUtilities.dp(560),
                    Math.round(AndroidUtilities.displaySize.y * 0.60f)
            );
            reservedPreviewHeight = estimatePreviewHeight(messages);
            previewFrame.setMinimumHeight(reservedPreviewHeight);
            scrollView.setOnScrollChangeListener(
                    (view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                        if (previewReady) {
                            QuoteCardView card = quoteCard;
                            if (card != null && bitmapPreview == null) {
                                card.updateVisibleMediaState();
                            }
                        }
                    }
            );
            scrollView.addView(previewFrame, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
            ));
            root.addView(scrollView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    reservedPreviewHeight
            ));
            LinearLayout secondaryActions = new LinearLayout(context);
            secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
            secondaryActions.setGravity(Gravity.CENTER);

            saveButton = new ButtonWithCounterView(context, resourcesProvider).setRound().setNeutral();
            saveButton.setText(LocaleController.getString(R.string.SaveToGallery), false);
            saveButton.setOnClickListener(view -> export(ExportAction.SAVE));
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(48), 1f);
            half.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(8));
            secondaryActions.addView(saveButton, half);

            shareButton = new ButtonWithCounterView(context, resourcesProvider).setRound().setNeutral();
            shareButton.setText(LocaleController.getString(R.string.ShareFile), false);
            shareButton.setOnClickListener(view -> export(ExportAction.SHARE));
            half = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(48), 1f);
            half.setMargins(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            secondaryActions.addView(shareButton, half);
            root.addView(secondaryActions, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            sendButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            sendButton.setText(LocaleController.getString(R.string.NM_QC_SendHere), false);
            sendButton.setOnClickListener(view -> export(ExportAction.SEND));
            sendButton.setVisibility(
                    chatActivity.canSendMessage() && !chatActivity.isInScheduleMode() && !chatActivity.isReport()
                            ? View.VISIBLE
                            : View.GONE
            );
            root.addView(sendButton, linear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 6));
            setPreviewActionsReady(previewReady);

            BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
            builder.setApplyBottomPadding(false);
            sheet = builder.setNativeCustomView(root).create();
            sheet.useBackgroundTopPadding = false;
            sheet.setOnDismissListener(this::onDismiss);
            sheet.setDelegate(new BottomSheet.BottomSheetDelegate() {
                @Override
                public void onOpenAnimationEnd() {
                    schedulePreviewBuild();
                }
            });
        }

        boolean show() {
            ++previewGeneration;
            if (chatActivity.showDialog(sheet, false, dialog -> onDismiss()) == null) {
                onDismiss();
                return false;
            }
            return true;
        }

        private void schedulePreviewBuild() {
            int generation = previewGeneration;
            if (!isPreviewBuildValid(generation) || previewBuildStartedGeneration == generation) {
                return;
            }
            previewBuildStartedGeneration = generation;
            previewFrame.postOnAnimation(() -> startPreviewBuild(generation));
        }

        private FrameLayout.LayoutParams cardLayoutParams() {
            return new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL
            );
        }

        private static int estimatePreviewHeight(ArrayList<MessageObject> messages) {
            int maxHeight = Math.min(
                    AndroidUtilities.dp(560),
                    Math.round(AndroidUtilities.displaySize.y * 0.60f)
            );
            int estimatedDp = 108;
            int count = Math.min(messages.size(), 6);
            for (int i = 0; i < count; i++) {
                MessageObject message = messages.get(i);
                int rowDp = 68;
                CharSequence text = message != null ? message.messageText : null;
                if (text != null && !text.toString().trim().isEmpty()) {
                    int estimatedLines = Math.max(1, (text.length() + 31) / 32);
                    rowDp += Math.min(260, Math.max(0, estimatedLines - 1) * 19);
                }
                if (message != null && (message.isPhoto()
                        || message.isVideo()
                        || message.isGif()
                        || message.isRoundVideo()
                        || message.isSticker()
                        || message.isAnimatedSticker())) {
                    rowDp += 112;
                }
                estimatedDp += rowDp;
            }
            return Math.min(maxHeight, Math.max(AndroidUtilities.dp(180), AndroidUtilities.dp(estimatedDp)));
        }

        private void startPreviewBuild(int generation) {
            if (!isPreviewBuildValid(generation)) return;
            try {
                QuoteCardView card = new QuoteCardView(activity, chatActivity, messages);
                card.setAlpha(1f);
                card.setScaleX(1f);
                card.setScaleY(1f);
                card.setVisibility(View.INVISIBLE);
                card.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (previewReady && bitmapPreview == null && !dismissed) {
                        syncPreviewViewportHeight(card);
                    }
                });
                quoteCard = card;
                previewFrame.addView(card, 0, cardLayoutParams());
                previewFrame.postOnAnimation(() -> buildPreviewStep(generation));
            } catch (Throwable error) {
                FileLog.e(error);
                failPreview(generation);
            }
        }

        private void buildPreviewStep(int generation) {
            QuoteCardView card = quoteCard;
            if (!isPreviewBuildValid(generation) || card == null) return;
            try {
                if (card.buildNextSegment()) {
                    previewFrame.postOnAnimation(() -> buildPreviewStep(generation));
                    return;
                }
                previewFrame.postOnAnimation(() -> finishPreview(generation));
            } catch (Throwable error) {
                FileLog.e(error);
                failPreview(generation);
            }
        }

        private void finishPreview(int generation) {
            QuoteCardView card = quoteCard;
            if (!isPreviewBuildValid(generation) || card == null || !card.isBuildComplete()) return;
            card.refreshMediaState();
            waitForPreviewMedia(
                    generation,
                    card,
                    SystemClock.uptimeMillis() + MEDIA_PREVIEW_SETTLE_TIMEOUT_MS,
                    0
            );
        }

        private void waitForPreviewMedia(
                int generation,
                QuoteCardView card,
                long deadline,
                int frame
        ) {
            if (!isPreviewBuildValid(generation) || quoteCard != card) return;
            card.setStaticMediaActive(true);
            boolean pending = card.hasPendingStaticPreviewMedia();
            boolean presentationReady = card.isPresentationReady();
            if (presentationReady) {
                int width = card.getMeasuredWidth();
                int height = card.getMeasuredHeight();
                if (width == settledPreviewWidth && height == settledPreviewHeight) {
                    stablePreviewLayoutFrames++;
                } else {
                    settledPreviewWidth = width;
                    settledPreviewHeight = height;
                    stablePreviewLayoutFrames = 0;
                }
                if (syncPreviewViewportHeight(card)
                        && SystemClock.uptimeMillis() < deadline) {
                    previewFrame.postOnAnimation(
                            () -> waitForPreviewMedia(generation, card, deadline, frame + 1)
                    );
                    return;
                }
            }
            if ((frame < MEDIA_PREVIEW_SETTLE_MIN_FRAMES
                    || !presentationReady
                    || pending
                    || stablePreviewLayoutFrames < 1)
                    && SystemClock.uptimeMillis() < deadline) {
                previewFrame.postOnAnimation(
                        () -> waitForPreviewMedia(generation, card, deadline, frame + 1)
                );
                return;
            }
            long pixels = (long) card.getWidth() * card.getHeight();
            boolean snapshotCandidate = pixels > MAX_LIVE_PREVIEW_PIXELS
                    && !card.requiresLivePreview();
            if (pending || !presentationReady || !snapshotCandidate) {
                completePreview(generation, card);
            } else {
                createBitmapPreview(generation, card);
            }
        }

        private boolean syncPreviewViewportHeight(QuoteCardView card) {
            int cardHeight = card.getMeasuredHeight();
            if (cardHeight <= 0) return false;
            int desiredHeight = Math.min(
                    maxPreviewHeight,
                    Math.max(
                            AndroidUtilities.dp(180),
                            cardHeight + previewFrame.getPaddingTop() + previewFrame.getPaddingBottom()
                    )
            );
            ViewGroup.LayoutParams layoutParams = scrollView.getLayoutParams();
            boolean changed = previewFrame.getMinimumHeight() != 0
                    || layoutParams == null
                    || layoutParams.height != desiredHeight;
            if (!changed) return false;
            previewFrame.setMinimumHeight(0);
            if (layoutParams != null) {
                layoutParams.height = desiredHeight;
                scrollView.setLayoutParams(layoutParams);
            }
            rootView.requestLayout();
            return true;
        }

        private void createBitmapPreview(int generation, QuoteCardView card) {
            int sourceWidth = card.getWidth();
            int sourceHeight = card.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                completePreview(generation, card);
                return;
            }
            double scale = Math.min(
                    1.0,
                    Math.sqrt(MAX_SNAPSHOT_PREVIEW_PIXELS / (double) ((long) sourceWidth * sourceHeight))
            );
            int longestSide = Math.max(sourceWidth, sourceHeight);
            if (longestSide * scale > MAX_SNAPSHOT_PREVIEW_SIDE) {
                scale = MAX_SNAPSHOT_PREVIEW_SIDE / (double) longestSide;
            }
            int bitmapWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
            int bitmapHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
            float bitmapScale = (float) scale;
            EXPORT_QUEUE.postRunnable(() -> {
                if (dismissed || generation != previewGeneration) return;
                Bitmap bitmap;
                try {
                    bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
                } catch (Throwable error) {
                    FileLog.e(error);
                    AndroidUtilities.runOnUIThread(() -> completePreview(generation, card));
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isPreviewBuildValid(generation) || quoteCard != card) {
                        recycleBitmapLater(bitmap);
                        return;
                    }
                    previewBitmap = bitmap;
                    card.setPreviewSnapshotMode(true);
                    drawBitmapPreview(generation, card, bitmap, bitmapScale);
                });
            });
        }

        private void drawBitmapPreview(
                int generation,
                QuoteCardView card,
                Bitmap bitmap,
                float scale
        ) {
            if (!isPreviewBuildValid(generation) || quoteCard != card || previewBitmap != bitmap) {
                card.setPreviewSnapshotMode(false);
                if (previewBitmap == bitmap) previewBitmap = null;
                recycleBitmapLater(bitmap);
                return;
            }
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(scale, scale);
                card.draw(canvas);
                canvas.setBitmap(null);
            } catch (Throwable error) {
                FileLog.e(error);
                card.setPreviewSnapshotMode(false);
                previewBitmap = null;
                recycleBitmapLater(bitmap);
                completePreview(generation, card);
                return;
            }
            card.setPreviewSnapshotMode(false);
            QuoteBitmapPreviewView preview = new QuoteBitmapPreviewView(activity, bitmap);
            bitmapPreview = preview;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    card.getHeight(),
                    Gravity.CENTER_HORIZONTAL
            );
            previewFrame.addView(preview, 1, params);
            card.setVisibility(View.INVISIBLE);
            card.setStaticMediaActive(false);
            completePreview(generation, card);
        }

        private void completePreview(int generation, QuoteCardView card) {
            if (!isPreviewBuildValid(generation) || quoteCard != card || previewReady) return;
            View content = bitmapPreview != null ? bitmapPreview : card;
            content.animate().cancel();
            content.setAlpha(SharedConfig.animationsEnabled() ? 0f : 1f);
            content.setScaleX(SharedConfig.animationsEnabled() ? PREVIEW_REVEAL_SCALE : 1f);
            content.setScaleY(SharedConfig.animationsEnabled() ? PREVIEW_REVEAL_SCALE : 1f);
            content.setTranslationY(SharedConfig.animationsEnabled() ? AndroidUtilities.dp(2) : 0f);
            content.setVisibility(View.VISIBLE);
            previewReady = true;
            setPreviewActionsReady(true, true);
            if (SharedConfig.animationsEnabled()) {
                previewFrame.postOnAnimation(() -> {
                    if (!isPreviewBuildValid(generation) || quoteCard != card
                            || content != (bitmapPreview != null ? bitmapPreview : quoteCard)) {
                        return;
                    }
                    content.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(PREVIEW_REVEAL_DURATION)
                            .setInterpolator(CubicBezierInterpolator.EASE_BOTH)
                            .withEndAction(() -> {
                                if (isPreviewBuildValid(generation) && bitmapPreview == null) {
                                    card.updateVisibleMediaState();
                                }
                            })
                            .start();
                });
            } else if (bitmapPreview == null) {
                card.updateVisibleMediaState();
            }
        }

        private static void recycleBitmapLater(Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled()) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (!bitmap.isRecycled()) bitmap.recycle();
            }, 500L);
        }

        private void setPreviewActionsReady(boolean ready) {
            setPreviewActionsReady(ready, false);
        }

        private void setPreviewActionsReady(boolean ready, boolean animated) {
            sendButton.setEnabled(ready);
            saveButton.setEnabled(ready);
            shareButton.setEnabled(ready);
            float alpha = ready ? 1f : 0.48f;
            setActionButtonAlpha(sendButton, alpha, animated);
            setActionButtonAlpha(saveButton, alpha, animated);
            setActionButtonAlpha(shareButton, alpha, animated);
        }

        private void setActionButtonAlpha(View button, float alpha, boolean animated) {
            button.animate().cancel();
            if (animated && SharedConfig.animationsEnabled()) {
                button.animate()
                        .alpha(alpha)
                        .setDuration(PREVIEW_REVEAL_DURATION)
                        .setInterpolator(CubicBezierInterpolator.EASE_BOTH)
                        .start();
            } else {
                button.setAlpha(alpha);
            }
        }

        private boolean isPreviewBuildValid(int generation) {
            return !dismissed
                    && generation == previewGeneration
                    && activity != null
                    && !activity.isFinishing()
                    && !activity.isDestroyed();
        }

        private void failPreview(int generation) {
            if (!isPreviewBuildValid(generation)) return;
            showError(chatActivity, R.string.NM_QC_Failed);
            sheet.dismiss();
        }

        private void onDismiss() {
            if (dismissed) return;
            dismissed = true;
            previewGeneration++;
            exportGeneration++;
            sendButton.animate().cancel();
            saveButton.animate().cancel();
            shareButton.animate().cancel();
            if (quoteCard != null) {
                quoteCard.animate().cancel();
                quoteCard.release();
            }
            if (bitmapPreview != null) {
                bitmapPreview.animate().cancel();
                bitmapPreview.release();
                bitmapPreview = null;
                previewBitmap = null;
            } else if (previewBitmap != null) {
                recycleBitmapLater(previewBitmap);
                previewBitmap = null;
            }
        }

        private void export(ExportAction action) {
            if (exporting || dismissed || !previewReady || quoteCard == null) {
                return;
            }
            boolean roundExportCorners = shouldRoundExportCorners(action);
            if (exportedFile != null
                    && exportedFile.isFile()
                    && exportedHasRoundedCorners == roundExportCorners) {
                completeAction(action, exportedFile);
                return;
            }
            exporting = true;
            setBusy(true, action);
            quoteCard.refreshMediaState();
            quoteCard.setStaticMediaActive(true);
            waitForExportMedia(
                    action,
                    SystemClock.uptimeMillis() + MEDIA_EXPORT_SETTLE_TIMEOUT_MS
            );
        }

        private void waitForExportMedia(ExportAction action, long deadline) {
            if (dismissed || !exporting || quoteCard == null) return;
            if (quoteCard.hasPendingStaticPreviewMedia()
                    && SystemClock.uptimeMillis() < deadline) {
                quoteCard.postOnAnimation(() -> waitForExportMedia(action, deadline));
                return;
            }
            quoteCard.postOnAnimation(() -> render(action));
        }

        private void render(ExportAction action) {
            if (dismissed) return;
            if (activity.isFinishing() || activity.isDestroyed()) {
                onDismiss();
                return;
            }
            int width = quoteCard.getWidth();
            int height = quoteCard.getHeight();
            if (width <= 0 || height <= 0) {
                exportFailed(exportGeneration);
                return;
            }

            quoteCard.setStaticMediaActive(true);
            Runtime runtime = Runtime.getRuntime();
            long memoryClass = runtime.maxMemory();
            long processMemoryAvailable = Math.max(
                    0L,
                    memoryClass - (runtime.totalMemory() - runtime.freeMemory())
            );
            ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo);
            }
            boolean lowMemoryDevice = memoryClass <= 320L * 1024L * 1024L
                    || activityManager != null && activityManager.isLowRamDevice()
                    || memoryInfo.lowMemory;
            long basePixelLimit = lowMemoryDevice
                    ? LOW_MEMORY_EXPORT_PIXELS
                    : MAX_EXPORT_PIXELS;
            long processPixelBudget = Math.max(1_000_000L, processMemoryAvailable / 8L);
            long pixelLimit = Math.min(basePixelLimit, processPixelBudget);
            double scale = 1.0;
            long pixels = (long) width * height;
            if (pixels > pixelLimit) {
                scale = Math.sqrt(pixelLimit / (double) pixels);
            }
            int longest = Math.max(width, height);
            if (longest * scale > MAX_EXPORT_SIDE) {
                scale = Math.min(scale, MAX_EXPORT_SIDE / (double) longest);
            }
            int outputWidth = Math.max(1, (int) Math.round(width * scale));
            int outputHeight = Math.max(1, (int) Math.round(height * scale));
            boolean sendAsDocument = shouldSendAsDocument(outputWidth, outputHeight);
            boolean roundExportCorners = action == ExportAction.SEND && sendAsDocument;
            float outputScale = (float) scale;
            int generation = ++exportGeneration;

            EXPORT_QUEUE.postRunnable(() -> {
                if (dismissed || generation != exportGeneration) return;
                Bitmap bitmap;
                try {
                    bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
                } catch (Throwable error) {
                    FileLog.e(error);
                    AndroidUtilities.runOnUIThread(() -> exportFailed(generation));
                    return;
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (!isExportValid(generation)) {
                        bitmap.recycle();
                        return;
                    }
                    if (quoteCard.getWidth() != width || quoteCard.getHeight() != height) {
                        bitmap.recycle();
                        quoteCard.postOnAnimation(() -> render(action));
                        return;
                    }
                    try {
                        Canvas canvas = new Canvas(bitmap);
                        canvas.scale(outputScale, outputScale);
                        quoteCard.setDrawingToBitmap(true, !roundExportCorners);
                        quoteCard.draw(canvas);
                        canvas.setBitmap(null);
                    } catch (Throwable error) {
                        FileLog.e(error);
                        bitmap.recycle();
                        exportFailed(generation);
                        return;
                    } finally {
                        quoteCard.setDrawingToBitmap(false, false);
                    }
                    writeBitmap(
                            action,
                            bitmap,
                            generation,
                            sendAsDocument,
                            roundExportCorners
                    );
                });
            });
        }

        private void writeBitmap(
                ExportAction action,
                Bitmap bitmap,
                int generation,
                boolean sendAsDocument,
                boolean roundExportCorners
        ) {
            EXPORT_QUEUE.postRunnable(() -> {
                if (dismissed || generation != exportGeneration) {
                    bitmap.recycle();
                    return;
                }
                File output = null;
                try {
                    File directory = getCacheDirectory();
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IOException("Unable to create quote cache directory");
                    }
                    output = new File(directory, String.format(
                            Locale.US,
                            "quote_%d_%d.png",
                            System.currentTimeMillis(),
                            generation
                    ));
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw new IOException("Bitmap compression failed");
                        }
                    }
                } catch (Throwable error) {
                    FileLog.e(error);
                    if (output != null && output.exists()) output.delete();
                    output = null;
                } finally {
                    bitmap.recycle();
                }
                File result = output;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isExportValid(generation)) {
                        if (result != null) result.delete();
                        return;
                    }
                    if (result == null) {
                        exportFailed(generation);
                        return;
                    }
                    scheduleCacheCleanup(result);
                    exportedFile = result;
                    exportedAsDocument = sendAsDocument;
                    exportedHasRoundedCorners = roundExportCorners;
                    exporting = false;
                    setBusy(false, null);
                    completeAction(action, result);
                });
            });
        }

        private boolean isExportValid(int generation) {
            return !dismissed
                    && generation == exportGeneration
                    && !activity.isFinishing()
                    && !activity.isDestroyed();
        }

        private void completeAction(ExportAction action, File file) {
            if (dismissed || activity.isFinishing() || activity.isDestroyed()) return;
            if (action == ExportAction.SEND) {
                SendMessagesHelper.prepareSendingPhoto(
                        chatActivity.getAccountInstance(),
                        file.getAbsolutePath(),
                        null,
                        null,
                        chatActivity.getDialogId(),
                        null,
                        chatActivity.getThreadMessage(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        true,
                        0,
                        0,
                        chatActivity.getChatMode(),
                        exportedAsDocument,
                        null,
                        chatActivity.getMessageChatSendParams(),
                        0,
                        0,
                        chatActivity.getSendMonoForumPeerId(),
                        chatActivity.getSendMessageSuggestionParams()
                );
                sheet.dismiss();
            } else if (action == ExportAction.SAVE) {
                if (saving) return;
                saving = true;
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                        && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    synchronized (LEGACY_SAVE_LOCK) {
                        if (pendingLegacySave != null) {
                            QuoteSheet previousSheet = pendingLegacySave.quoteSheet.get();
                            if (previousSheet != null) previousSheet.saving = false;
                        }
                        pendingLegacySave = new PendingLegacySave(this, file);
                    }
                    try {
                        activity.requestPermissions(
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                LEGACY_SAVE_PERMISSION_REQUEST
                        );
                    } catch (Throwable error) {
                        FileLog.e(error);
                        synchronized (LEGACY_SAVE_LOCK) {
                            pendingLegacySave = null;
                        }
                        saving = false;
                        showError(chatActivity, R.string.NM_QC_Failed);
                    }
                    return;
                }
                saveToGallery(file);
            } else {
                try {
                    Uri uri = FileProvider.getUriForFile(
                            activity,
                            ApplicationLoader.getApplicationId() + ".provider",
                            file
                    );
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.setClipData(ClipData.newRawUri("quote", uri));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(
                            intent,
                            LocaleController.getString(R.string.NM_QC_Create)
                    ));
                } catch (ActivityNotFoundException error) {
                    showError(chatActivity, R.string.NoHandleAppInstalled);
                } catch (Throwable error) {
                    FileLog.e(error);
                    showError(chatActivity, R.string.NM_QC_Failed);
                }
            }
        }

        private boolean shouldSendAsDocument(int width, int height) {
            if (width <= 0 || height <= width * MAX_INLINE_PHOTO_ASPECT_RATIO) {
                return false;
            }
            TLRPC.Chat chat = chatActivity.getCurrentChat();
            return chat == null || ChatObject.canSendDocument(chat);
        }

        private boolean shouldRoundExportCorners(ExportAction action) {
            int width = quoteCard.getWidth();
            int height = quoteCard.getHeight();
            return action == ExportAction.SEND
                    && width > 0
                    && height > 0
                    && shouldSendAsDocument(width, height);
        }

        private void saveToGallery(File file) {
            saveQuoteToGallery(file, uri -> {
                saving = false;
                if (uri == null) {
                    if (!dismissed) showError(chatActivity, R.string.NM_QC_Failed);
                } else if (!dismissed) {
                    showSaved(chatActivity);
                }
            });
        }

        private void exportFailed(int generation) {
            if (dismissed || generation != exportGeneration) return;
            exporting = false;
            if (quoteCard != null) {
                quoteCard.updateVisibleMediaState();
            }
            setBusy(false, null);
            showError(chatActivity, R.string.NM_QC_Failed);
        }

        private void setBusy(boolean busy, ExportAction action) {
            if (dismissed) return;
            sendButton.animate().cancel();
            saveButton.animate().cancel();
            shareButton.animate().cancel();
            sendButton.setLoading(busy && action == ExportAction.SEND);
            saveButton.setLoading(busy && action == ExportAction.SAVE);
            shareButton.setLoading(busy && action == ExportAction.SHARE);
            sendButton.setEnabled(!busy);
            saveButton.setEnabled(!busy);
            shareButton.setEnabled(!busy);
            sendButton.setAlpha(busy && action != ExportAction.SEND ? 0.55f : 1f);
            saveButton.setAlpha(busy && action != ExportAction.SAVE ? 0.55f : 1f);
            shareButton.setAlpha(busy && action != ExportAction.SHARE ? 0.55f : 1f);
        }
    }

    private static final class QuoteBitmapPreviewView extends View {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF destination = new RectF();
        private Bitmap bitmap;

        QuoteBitmapPreviewView(Context context, Bitmap bitmap) {
            super(context);
            this.bitmap = bitmap;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap current = bitmap;
            if (current == null || current.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;
            destination.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(current, null, destination, bitmapPaint);
        }

        void release() {
            Bitmap current = bitmap;
            bitmap = null;
            invalidate();
            QuoteSheet.recycleBitmapLater(current);
        }
    }

    private static final class QuoteCardView extends LinearLayout {
        private static final EntityView.EntityViewDelegate INERT_ENTITY_DELEGATE = new EntityView.EntityViewDelegate() {
            @Override
            public boolean onEntitySelected(EntityView entityView) {
                return false;
            }

            @Override
            public boolean onEntityLongClicked(EntityView entityView) {
                return false;
            }

            @Override
            public boolean allowInteraction(EntityView entityView) {
                return false;
            }

            @Override
            public int[] getCenterLocation(EntityView entityView) {
                return new int[]{0, 0};
            }

            @Override
            public void getTransformedTouch(float x, float y, float[] output) {
                if (output == null) return;
                if (output.length > 0) output[0] = x;
                if (output.length > 1) output[1] = y;
            }

            @Override
            public float getCropRotation() {
                return 0f;
            }
        };

        private final Path clipPath = new Path();
        private final Rect visibleRect = new Rect();
        private final int[] childLocation = new int[2];
        private final RectF bounds = new RectF();
        private final RectF borderBounds = new RectF();
        private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean singleMessageQuote;
        private float cornerRadius = AndroidUtilities.dpf2(24);
        private final Drawable wallpaper;
        private final Theme.ResourcesProvider resourcesProvider;
        private final int wallpaperColor;
        private final int currentAccount;
        private final Context context;
        private final ArrayList<ArrayList<MessageObject>> pendingSegments;
        private final ArrayList<MessageEntityView> entities = new ArrayList<>();
        private final boolean requiresLivePreview;
        private ImageReceiver headerAvatarReceiver;
        private int nextSegment;
        private boolean drawingToBitmap;
        private boolean bypassOuterRoundingForBitmap;
        private boolean telegramMediaPreview;
        private boolean released;

        QuoteCardView(
                Context context,
                ChatActivity chatActivity,
                ArrayList<MessageObject> messages
        ) {
            super(context);
            this.context = context;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            setPadding(
                    AndroidUtilities.dp(14),
                    AndroidUtilities.dp(14),
                    AndroidUtilities.dp(14),
                    AndroidUtilities.dp(16)
            );

            resourcesProvider = chatActivity.getResourceProvider();
            currentAccount = chatActivity.getCurrentAccount();
            singleMessageQuote = messages.size() == 1;
            requiresLivePreview = containsLivePreviewContent(messages);
            Drawable source = null;
            if (resourcesProvider instanceof MessagePreviewView.ResourcesDelegate) {
                source = ((MessagePreviewView.ResourcesDelegate) resourcesProvider).getWallpaperDrawable();
            }
            Drawable clonedWallpaper = cloneDrawable(source);
            if (clonedWallpaper == null) {
                clonedWallpaper = cloneDrawable(Theme.getCachedWallpaperNonBlocking());
            }
            wallpaper = clonedWallpaper;
            boolean dark = resourcesProvider != null
                    ? resourcesProvider.isDark()
                    : Theme.isCurrentThemeDark();
            wallpaperColor = resolveWallpaperColor(wallpaper, resourcesProvider, dark);
            int bubbleColor = Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider);
            int shadowColor = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
            Theme.ThemeInfo activeTheme = Theme.getActiveTheme();
            boolean darkMonet = dark && activeTheme != null && activeTheme.isMonet();
            int previewSurfaceColor = darkMonet
                    ? Theme.getColor(Theme.key_buttonNeutral, resourcesProvider)
                    : bubbleColor;
            overlayPaint.setColor(ColorUtils.setAlphaComponent(
                    previewSurfaceColor,
                    darkMonet ? 72 : dark ? 24 : 14
            ));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(AndroidUtilities.dpf2(1));
            borderPaint.setColor(ColorUtils.setAlphaComponent(
                    shadowColor,
                    darkMonet ? 112 : dark ? 92 : 58
            ));

            addHeader(context, chatActivity, messages);
            pendingSegments = splitIntoNativeGroups(messages);
        }

        boolean buildNextSegment() {
            if (released || nextSegment >= pendingSegments.size()) return false;
            ArrayList<MessageObject> segment = pendingSegments.get(nextSegment++);
            boolean more = nextSegment < pendingSegments.size();
            addMessageEntity(context, segment, more);
            return more;
        }

        boolean isBuildComplete() {
            return nextSegment >= pendingSegments.size();
        }

        private void addHeader(Context context, ChatActivity chatActivity, ArrayList<MessageObject> messages) {
            FrameLayout header = new FrameLayout(context);
            header.setMinimumHeight(AndroidUtilities.dp(68));

            int serviceBackground = Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider);
            int accentColor = Theme.getColor(Theme.key_chat_serviceLink, resourcesProvider);
            int textColor = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
            int detailColor = ColorUtils.setAlphaComponent(textColor, 184);
            boolean dark = resourcesProvider != null
                    ? resourcesProvider.isDark()
                    : Theme.isCurrentThemeDark();
            int bubbleColor = ColorUtils.compositeColors(serviceBackground, wallpaperColor);

            GradientDrawable background = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{
                            ColorUtils.blendARGB(bubbleColor, accentColor, dark ? 0.20f : 0.11f),
                            bubbleColor,
                            ColorUtils.blendARGB(bubbleColor, wallpaperColor, dark ? 0.06f : 0.03f)
                    }
            );
            background.setCornerRadius(AndroidUtilities.dp(18));
            background.setStroke(
                    AndroidUtilities.dp(1),
                    ColorUtils.setAlphaComponent(accentColor, dark ? 72 : 44)
            );
            header.setBackground(background);

            View accent = new View(context);
            accent.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(2), accentColor));
            FrameLayout.LayoutParams accentParams = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(3),
                    AndroidUtilities.dp(40),
                    Gravity.START | Gravity.CENTER_VERTICAL
            );
            accentParams.setMarginStart(AndroidUtilities.dp(8));
            header.addView(accent, accentParams);

            TLRPC.Chat sourceChat = chatActivity.getCurrentChat();
            TLRPC.User sourceUser = chatActivity.getCurrentUser();
            TLObject sourcePeer = sourceChat != null ? sourceChat : sourceUser;

            AvatarDrawable avatarDrawable = new AvatarDrawable(resourcesProvider);
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(21));
            avatar.getImageReceiver().setCurrentAccount(currentAccount);
            headerAvatarReceiver = avatar.getImageReceiver();
            if (sourcePeer != null) {
                avatarDrawable.setInfo(currentAccount, sourcePeer);
                avatar.setForUserOrChat(sourcePeer, avatarDrawable);
            } else {
                avatarDrawable.setInfo(0, LocaleController.getString(R.string.AppName), null);
                avatar.setImageDrawable(avatarDrawable);
            }
            FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(42),
                    AndroidUtilities.dp(42),
                    Gravity.START | Gravity.CENTER_VERTICAL
            );
            avatarParams.setMarginStart(AndroidUtilities.dp(17));
            header.addView(avatar, avatarParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(VERTICAL);
            textColumn.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL
            );
            textParams.setMarginStart(AndroidUtilities.dp(69));
            textParams.setMarginEnd(AndroidUtilities.dp(49));
            header.addView(textColumn, textParams);

            TextView source = new TextView(context);
            String sourceTitle;
            if (sourceChat != null) {
                sourceTitle = sourceChat.title;
            } else if (sourceUser != null) {
                sourceTitle = UserObject.getUserName(sourceUser);
            } else {
                sourceTitle = LocaleController.getString(R.string.AppName);
            }
            source.setText(sourceTitle);
            source.setTextColor(textColor);
            source.setTextSize(16);
            source.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
            source.setSingleLine(true);
            source.setIncludeFontPadding(false);
            source.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textColumn.addView(source, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            TextView details = new TextView(context);
            details.setText(formatQuoteDate(messages.get(0).messageOwner.date));
            details.setTextColor(detailColor);
            details.setTextSize(13);
            details.setSingleLine(true);
            details.setIncludeFontPadding(false);
            details.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textColumn.addView(details, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            ImageView quoteIcon = new ImageView(context);
            quoteIcon.setImageResource(R.drawable.menu_select_quote);
            quoteIcon.setScaleType(ImageView.ScaleType.CENTER);
            quoteIcon.setColorFilter(accentColor);
            quoteIcon.setBackground(Theme.createCircleDrawable(
                    AndroidUtilities.dp(32),
                    ColorUtils.setAlphaComponent(accentColor, dark ? 40 : 26)
            ));
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(32),
                    AndroidUtilities.dp(32),
                    Gravity.END | Gravity.CENTER_VERTICAL
            );
            iconParams.setMarginEnd(AndroidUtilities.dp(10));
            header.addView(quoteIcon, iconParams);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, AndroidUtilities.dp(8));
            addView(header, params);
        }

        private static String formatQuoteDate(long timestamp) {
            Calendar today = Calendar.getInstance();
            Calendar messageDate = Calendar.getInstance();
            messageDate.setTimeInMillis(timestamp * 1000L);
            if (isSameDay(today, messageDate)) {
                return capitalizeQuoteDate(LocaleController.getString(R.string.ShortToday));
            }
            today.add(Calendar.DAY_OF_YEAR, -1);
            if (isSameDay(today, messageDate)) {
                return capitalizeQuoteDate(LocaleController.getString(R.string.Yesterday));
            }
            return LocaleController.formatDateChat(timestamp);
        }

        private static String capitalizeQuoteDate(String value) {
            if (value == null || value.isEmpty()) return value;
            int firstCodePointEnd = value.offsetByCodePoints(0, 1);
            Locale locale = LocaleController.getInstance().getCurrentLocale();
            if (locale == null) locale = Locale.getDefault();
            return value.substring(0, firstCodePointEnd).toUpperCase(locale)
                    + value.substring(firstCodePointEnd);
        }

        private static boolean isSameDay(Calendar first, Calendar second) {
            return first.get(Calendar.ERA) == second.get(Calendar.ERA)
                    && first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                    && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
        }

        private void addMessageEntity(
                Context context,
                ArrayList<MessageObject> segment,
                boolean addBottomGap
        ) {
            MessageEntityView entity = new MessageEntityView(
                    context,
                    new PointF(0, 0),
                    segment,
                    null,
                    false,
                    null,
                    true
            ) {
                @Override
                protected void updatePosition() {
                }

                @Override
                public boolean drawForBitmap() {
                    return drawingToBitmap;
                }
            };
            entity.setDelegate(INERT_ENTITY_DELEGATE);
            entity.setStaticPresentation(currentAccount, resourcesProvider);
            entities.add(entity);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.setMargins(0, 0, 0, addBottomGap ? AndroidUtilities.dp(2) : 0);
            addView(entity, params);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST
                    && MeasureSpec.getSize(widthMeasureSpec) > 0) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        MeasureSpec.getSize(widthMeasureSpec),
                        MeasureSpec.EXACTLY
                );
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        void refreshMediaState() {
            if (released) return;
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (child instanceof ChatMessageCell) {
                        ChatMessageCell cell = (ChatMessageCell) child;
                        cell.setForceDrawVideoThumbnail(true);
                        cell.setFullyDraw(true);
                        cell.shouldCheckVisibleOnScreen = true;
                    }
                }
            }
            if (getVisibility() == View.VISIBLE) {
                updateVisibleMediaState();
            } else {
                setStaticMediaActive(true);
            }
        }

        boolean hasPendingStaticPreviewMedia() {
            if (released) return false;
            if (isPendingHeaderAvatar(headerAvatarReceiver)) return true;
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                if (!entity.isStaticPresentationReady()) {
                    return true;
                }
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (child instanceof ChatMessageCell
                            && ((ChatMessageCell) child).hasPendingStaticPreviewMedia()) {
                        return true;
                    }
                    if (child instanceof ChatActionCell
                            && ((ChatActionCell) child).hasPendingStaticPreviewMedia()) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean isPresentationReady() {
            if (released || !isAttachedToWindow() || entities.size() != pendingSegments.size()) {
                return false;
            }
            for (int i = 0; i < entities.size(); i++) {
                if (!entities.get(i).isStaticPresentationReady()) return false;
            }
            return getMeasuredWidth() > 0 && getMeasuredHeight() > 0;
        }

        boolean requiresLivePreview() {
            return requiresLivePreview;
        }

        private static boolean isPendingHeaderAvatar(ImageReceiver receiver) {
            if (receiver == null) return false;
            boolean hasRemoteTarget = receiver.getImageKey() != null
                    || receiver.getMediaKey() != null
                    || receiver.getThumbKey() != null
                    || receiver.getImageLocation() != null
                    || receiver.getMediaLocation() != null
                    || receiver.getThumbLocation() != null;
            return (receiver.hasPendingImageRequest() || hasRemoteTarget)
                    && receiver.getBitmap() == null
                    && !receiver.hasNotThumbOrOnlyStaticThumb();
        }

        private static boolean containsLivePreviewContent(ArrayList<MessageObject> messages) {
            for (int i = 0; i < messages.size(); i++) {
                if (messageNeedsLivePreview(messages.get(i), true)) return true;
            }
            return false;
        }

        private static boolean messageNeedsLivePreview(MessageObject message, boolean inspectReply) {
            if (message == null) return false;
            if (message.type != MessageObject.TYPE_TEXT) return true;
            if (message.photoThumbs != null && !message.photoThumbs.isEmpty()) return true;
            if (hasAnimatedEmojiSpans(message.messageText)
                    || hasAnimatedEmojiSpans(message.caption)) {
                return true;
            }
            TLRPC.Message owner = message.messageOwner;
            if (owner == null) return true;
            TLRPC.MessageMedia media = MessageObject.getMedia(owner);
            if (media != null && !(media instanceof TLRPC.TL_messageMediaEmpty)) return true;
            if (owner.entities != null) {
                for (int i = 0; i < owner.entities.size(); i++) {
                    TLRPC.MessageEntity entity = owner.entities.get(i);
                    if (entity instanceof TLRPC.TL_messageEntityCustomEmoji
                            || entity instanceof TLRPC.TL_messageEntityTextUrl
                            && entity.url != null
                            && entity.url.startsWith("tg://emoji?id=")) {
                        return true;
                    }
                }
            }
            if (owner.reactions != null
                    && owner.reactions.results != null
                    && !owner.reactions.results.isEmpty()) {
                return true;
            }
            return inspectReply
                    && message.replyMessageObject != null
                    && messageNeedsLivePreview(message.replyMessageObject, false);
        }

        private static boolean hasAnimatedEmojiSpans(CharSequence text) {
            if (!(text instanceof Spanned) || text.length() == 0) return false;
            return ((Spanned) text).getSpans(
                    0,
                    text.length(),
                    AnimatedEmojiSpan.class
            ).length > 0;
        }

        void setStaticMediaActive(boolean active) {
            if (released) return;
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (!(child instanceof ChatMessageCell)) continue;
                    ChatMessageCell cell = (ChatMessageCell) child;
                    cell.shouldCheckVisibleOnScreen = true;
                    cell.setVisibleOnScreen(active, 0, 0);
                }
            }
        }

        void setPreviewSnapshotMode(boolean value) {
            if (released) return;
            if (value) {
                prepareStaticPreviewMediaForBitmap();
            }
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (!(child instanceof ChatMessageCell)) continue;
                    ChatMessageCell cell = (ChatMessageCell) child;
                    cell.shouldCheckVisibleOnScreen = !value;
                    if (value) {
                        cell.setVisibleOnScreen(true, 0, 0);
                    }
                }
            }
            if (!value && getVisibility() == View.VISIBLE) {
                updateVisibleMediaState();
            }
        }

        void setDrawingToBitmap(boolean value, boolean bypassOuterRounding) {
            if (released) return;
            drawingToBitmap = value;
            bypassOuterRoundingForBitmap = value && bypassOuterRounding;
            if (value) {
                prepareStaticPreviewMediaForBitmap();
                setLayerType(View.LAYER_TYPE_NONE, null);
            }
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                entity.prepareToDraw(value);
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (child instanceof ChatMessageCell) {
                        ChatMessageCell cell = (ChatMessageCell) child;
                        cell.shouldCheckVisibleOnScreen = !value;
                        if (value) {
                            cell.setVisibleOnScreen(true, 0, 0);
                        }
                    }
                }
            }
            if (!value) {
                updateVisibleMediaState();
            }
        }

        private void prepareStaticPreviewMediaForBitmap() {
            if (headerAvatarReceiver != null && headerAvatarReceiver.getDrawable() != null) {
                headerAvatarReceiver.setCurrentAlpha(1f);
            }
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (child instanceof ChatMessageCell) {
                        ((ChatMessageCell) child).prepareStaticPreviewMediaForBitmap();
                    } else if (child instanceof ChatActionCell) {
                        ((ChatActionCell) child).prepareStaticPreviewMediaForBitmap();
                    }
                }
            }
        }

        void updateVisibleMediaState() {
            if (released || drawingToBitmap || !isAttachedToWindow()) return;
            for (int i = 0; i < entities.size(); i++) {
                MessageEntityView entity = entities.get(i);
                for (int j = 0; j < entity.listView.getChildCount(); j++) {
                    View child = entity.listView.getChildAt(j);
                    if (!(child instanceof ChatMessageCell)) continue;
                    ChatMessageCell cell = (ChatMessageCell) child;
                    boolean visible = child.getGlobalVisibleRect(visibleRect);
                    float clipTop = 0f;
                    float clipBottom = 0f;
                    if (visible) {
                        child.getLocationOnScreen(childLocation);
                        clipTop = Math.max(0, visibleRect.top - childLocation[1]);
                        clipBottom = Math.max(
                                0,
                                childLocation[1] + child.getHeight() - visibleRect.bottom
                        );
                    }
                    cell.shouldCheckVisibleOnScreen = true;
                    cell.setVisibleOnScreen(visible, clipTop, clipBottom);
                }
            }
        }

        void release() {
            if (released) return;
            setStaticMediaActive(false);
            released = true;
            setLayerType(View.LAYER_TYPE_NONE, null);
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).prepareToDraw(false);
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            int mediaRadius = SharedConfig.bubbleRadius > 2
                    ? SharedConfig.bubbleRadius - 2
                    : SharedConfig.bubbleRadius;
            telegramMediaPreview = singleMessageQuote
                    && width > 0
                    && height <= width * MAX_INLINE_PHOTO_ASPECT_RATIO;
            cornerRadius = telegramMediaPreview
                    ? AndroidUtilities.dp(mediaRadius)
                    : AndroidUtilities.dpf2(24);
            bounds.set(0, 0, width, height);
            borderBounds.set(bounds);
            float halfStroke = borderPaint.getStrokeWidth() / 2f;
            borderBounds.inset(halfStroke, halfStroke);
            clipPath.rewind();
            if (width > 0 && height > 0) {
                clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            boolean applyOuterRounding = !drawingToBitmap || !bypassOuterRoundingForBitmap;
            int save = canvas.save();
            if (applyOuterRounding) {
                canvas.clipPath(clipPath);
            }
            if (wallpaper != null) {
                drawWallpaper(canvas);
            } else {
                canvas.drawColor(wallpaperColor);
            }
            canvas.drawRect(bounds, overlayPaint);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
            if (applyOuterRounding && !telegramMediaPreview) {
                canvas.drawRoundRect(borderBounds, cornerRadius, cornerRadius, borderPaint);
            }
        }

        private void drawWallpaper(Canvas canvas) {
            if (wallpaper instanceof BitmapDrawable
                    && ((BitmapDrawable) wallpaper).getTileModeX() == Shader.TileMode.REPEAT) {
                int save = canvas.save();
                float scale = 2f / AndroidUtilities.density;
                canvas.scale(scale, scale);
                wallpaper.setBounds(
                        0,
                        0,
                        (int) Math.ceil(getWidth() / scale),
                        (int) Math.ceil(getHeight() / scale)
                );
                wallpaper.draw(canvas);
                canvas.restoreToCount(save);
                return;
            }
            if (wallpaper instanceof ColorDrawable
                    || wallpaper instanceof GradientDrawable
                    || wallpaper instanceof MotionBackgroundDrawable
                    && !((MotionBackgroundDrawable) wallpaper).hasPattern()) {
                wallpaper.setBounds(0, 0, getWidth(), getHeight());
                wallpaper.draw(canvas);
                return;
            }
            int intrinsicWidth = wallpaper.getIntrinsicWidth();
            int intrinsicHeight = wallpaper.getIntrinsicHeight();
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                wallpaper.setBounds(0, 0, getWidth(), getHeight());
                wallpaper.draw(canvas);
                return;
            }
            float scale = Math.max(
                    getWidth() / (float) intrinsicWidth,
                    getHeight() / (float) intrinsicHeight
            );
            int width = (int) Math.ceil(intrinsicWidth * scale);
            int height = (int) Math.ceil(intrinsicHeight * scale);
            int left = (getWidth() - width) / 2;
            int top = (getHeight() - height) / 2;
            wallpaper.setBounds(left, top, left + width, top + height);
            wallpaper.draw(canvas);
        }

        private static ArrayList<ArrayList<MessageObject>> splitIntoNativeGroups(ArrayList<MessageObject> messages) {
            ArrayList<ArrayList<MessageObject>> result = new ArrayList<>();
            Set<String> consumed = new HashSet<>();
            ArrayList<MessageObject> regularMessages = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                MessageObject message = messages.get(i);
                long groupId = message.getGroupIdForUse();
                if (groupId == 0) {
                    regularMessages.add(message);
                    continue;
                }
                String groupKey = message.getDialogId() + ":" + groupId;
                if (consumed.contains(groupKey)) continue;
                ArrayList<MessageObject> group = new ArrayList<>();
                for (int j = 0; j < messages.size(); j++) {
                    MessageObject candidate = messages.get(j);
                    if (candidate.getDialogId() == message.getDialogId()
                            && candidate.getGroupIdForUse() == groupId) {
                        group.add(candidate);
                    }
                }
                consumed.add(groupKey);
                if (group.size() <= 1) {
                    regularMessages.add(message);
                    continue;
                }
                flushRegularMessages(result, regularMessages);
                result.add(group);
            }
            flushRegularMessages(result, regularMessages);
            return result;
        }

        private static void flushRegularMessages(
                ArrayList<ArrayList<MessageObject>> result,
                ArrayList<MessageObject> regularMessages
        ) {
            if (regularMessages.isEmpty()) return;
            result.add(new ArrayList<>(regularMessages));
            regularMessages.clear();
        }

        private static Drawable cloneDrawable(Drawable source) {
            if (source instanceof MotionBackgroundDrawable) {
                MotionBackgroundDrawable motion = (MotionBackgroundDrawable) source;
                int[] colors = motion.getColors();
                if (colors != null && colors.length >= 4) {
                    MotionBackgroundDrawable copy = new MotionBackgroundDrawable(
                            colors[0], colors[1], colors[2], colors[3], false
                    );
                    Bitmap pattern = motion.getPatternBitmap();
                    if (pattern != null && !pattern.isRecycled()) {
                        copy.setPatternBitmap(motion.getIntensity(), pattern);
                        copy.setPatternColorFilter(copy.getPatternColor());
                    }
                    return copy;
                }
            }
            if (source == null || source.getConstantState() == null) return null;
            try {
                return source.getConstantState()
                        .newDrawable(ApplicationLoader.applicationContext.getResources())
                        .mutate();
            } catch (Throwable error) {
                FileLog.e(error);
                return null;
            }
        }

        private static int resolveWallpaperColor(
                Drawable wallpaper,
                Theme.ResourcesProvider resourcesProvider,
                boolean dark
        ) {
            if (wallpaper instanceof ColorDrawable) {
                return ColorUtils.setAlphaComponent(((ColorDrawable) wallpaper).getColor(), 255);
            }
            if (wallpaper instanceof MotionBackgroundDrawable) {
                int[] colors = ((MotionBackgroundDrawable) wallpaper).getColors();
                if (colors != null && colors.length >= 4) {
                    int average = colors[0];
                    for (int i = 1; i < colors.length; i++) {
                        if (colors[i] != 0) {
                            average = AndroidUtilities.getAverageColor(average, colors[i]);
                        }
                    }
                    return ColorUtils.setAlphaComponent(average, 255);
                }
            }

            int themeColor = Theme.getColor(Theme.key_chat_wallpaper, resourcesProvider);
            if (themeColor != 0 && themeColor != Theme.getDefaultColor(Theme.key_chat_wallpaper)) {
                return ColorUtils.setAlphaComponent(themeColor, 255);
            }

            Theme.ThemeInfo activeTheme = Theme.getActiveTheme();
            int fallback = activeTheme != null ? activeTheme.getPreviewBackgroundColor() : 0;
            if (fallback == 0
                    || activeTheme != null && activeTheme.isDark() != dark) {
                fallback = dark ? 0xff17212b : 0xffe1e9f0;
            }
            return ColorUtils.setAlphaComponent(fallback, 255);
        }
    }

    private static LinearLayout.LayoutParams linear(
            int width,
            int heightDp,
            int leftDp,
            int topDp,
            int rightDp,
            int bottomDp
    ) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width,
                heightDp < 0 ? heightDp : AndroidUtilities.dp(heightDp)
        );
        params.setMargins(
                AndroidUtilities.dp(leftDp),
                AndroidUtilities.dp(topDp),
                AndroidUtilities.dp(rightDp),
                AndroidUtilities.dp(bottomDp)
        );
        return params;
    }
}
