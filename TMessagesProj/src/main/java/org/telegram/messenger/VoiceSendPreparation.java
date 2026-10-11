/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.os.Process;
import android.system.Os;
import android.system.StructStat;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.WeakHashMap;

import java.util.LinkedHashMap;
public final class VoiceSendPreparation {
    public interface Listener {
        void onVoiceSendPrepared(Work work, boolean sent);

        default MessageObject.SendAnimationData getVoiceSendAnimation(Work work) {
            return null;
        }
    }

    private static final class QueueHolder {
        static final DispatchQueue queue = new DispatchQueue("voiceSendPreparation", true, Process.THREAD_PRIORITY_BACKGROUND);
    }
    private static final HashMap<String, Work> pending = new HashMap<>();
    private static final HashMap<String, Submission> submitted = new HashMap<>();
    private static final HashSet<Integer> observed = new HashSet<>();
    private static final LinkedHashMap<String, Outcome> outcomes = new LinkedHashMap<>();
    private static final NotificationCenter.NotificationCenterDelegate logout = (id, account, args) -> {
        for (Work work : pending.values()) {
            if (work.account == account) work.cancelled = true;
        }
        submitted.keySet().removeIf(key -> key.startsWith(account + ":"));
        outcomes.keySet().removeIf(key -> key.startsWith(account + ":"));
    };

    public static final class Outcome {
        public boolean wasPresented() { return presented; }
        private boolean presented;
        public final boolean needsConfirmation, notify;
        public final int scheduleDate, scheduleRepeatPeriod;
        private final int account, frames;
        private final long userId, dialogId, topicId, audioId, draftId, samples, duration;
        private final String path;
        private final boolean hasDraft;
        private final WeakReference<TLRPC.Document> audio;

        private Outcome(Work work) {
            account = work.account;
            userId = work.userId;
            dialogId = work.dialogId;
            topicId = work.topicId;
            audioId = work.audioId;
            draftId = work.draftId;
            samples = work.samples;
            frames = work.frames;
            duration = work.duration;
            path = work.path;
            hasDraft = work.draft != null;
            audio = new WeakReference<>(work.audio);
            needsConfirmation = work.needsConfirmation;
            notify = work.params.notify;
            scheduleDate = work.params.scheduleDate;
            scheduleRepeatPeriod = work.params.scheduleRepeatPeriod;
        }

        public boolean matches(int account, long dialogId, long topicId, TLRPC.Document audio, String path) {
            if (this.account != account || this.dialogId != dialogId || this.topicId != topicId
                    || !this.path.equals(path) || audio == null || audio.id != audioId
                    || !UserConfig.getInstance(account).isClientActivated()
                    || UserConfig.getInstance(account).getClientUserId() != userId) return false;
            MediaDataController.DraftVoice draft = MediaDataController.getInstance(account).getDraftVoice(dialogId, topicId);
            return hasDraft ? draft != null && this.path.equals(draft.path) && draft.id == draftId
                    && draft.samplesCount == samples && draft.writedFrame == frames && draft.recordTimeCount == duration
                    : draft == null && this.audio.get() == audio && MediaController.getInstance().recordingAudio == audio;
        }
    }

    public static Outcome getOutcome(int account, long dialogId, long topicId, TLRPC.Document audio, String path) {
        Outcome outcome = outcomes.get(key(account, path));
        return outcome != null && outcome.matches(account, dialogId, topicId, audio, path) ? outcome : null;
    }

    public static void acknowledgeOutcome(Outcome outcome) {
        outcome.presented = true;
    }

    public static void discardOutcome(Outcome outcome) {
        outcomes.remove(key(outcome.account, outcome.path), outcome);
    }
    private static final class Submission {
        final WeakHashMap<TLRPC.Document, Boolean> previews = new WeakHashMap<>();
        final WeakHashMap<MediaDataController.DraftVoice, Boolean> drafts = new WeakHashMap<>();
        boolean isUnused() {
            return previews.isEmpty() && drafts.isEmpty();
        }
    }

    private static final class Preview {
        final WeakReference<TLRPC.Document> audio;
        final long generation;
        Preview(TLRPC.Document audio, long generation) {
            this.audio = new WeakReference<>(audio);
            this.generation = generation;
        }
    }

    public static final class Work {
        public final int account;
        public final long userId, dialogId, topicId, generation;
        public final TLRPC.Document audio;
        public final String path;
        public final SendMessagesHelper.SendMessageParams params;
        private final long startMs, endMs, samples, duration, audioId, draftId;
        private final int frames;
        private final MediaDataController.DraftVoice draft;
        private final boolean previewEligible;
        private final long previewSendTime;
        private final WeakReference<Listener> presentationListener;
        private final WeakHashMap<Listener, Preview> listeners = new WeakHashMap<>();
        private final String key;
        private final Submission submission = new Submission();
        private volatile boolean cancelled;
        private File result;
        private StructStat source;
        private Throwable error;

        private boolean needsConfirmation;
        public boolean failed() {
            return error != null && !cancelled;
        }

        public boolean canPresentPreview(Listener listener, long now) {
            Preview preview = listeners.get(listener);
            return previewEligible && presentationListener.get() == listener && preview != null
                    && preview.audio.get() == audio && preview.generation == generation
                    && now >= previewSendTime && now - previewSendTime < 200;
        }

        public boolean matchesPreview(Listener listener, int account, long dialogId, long topicId,
                                      long generation, TLRPC.Document audio, String path) {
            Preview preview = listeners.get(listener);
            return preview != null && preview.audio.get() == audio && preview.generation == generation
                    && this.account == account && this.dialogId == dialogId && this.topicId == topicId
                    && this.path.equals(path);
        }

        private Work(int account, long topicId, long generation, SendMessagesHelper.SendMessageParams params,
                     long startMs, long endMs, long previewSendTime, Listener listener) {
            this.account = account;
            this.userId = UserConfig.getInstance(account).getClientUserId();
            this.dialogId = params.peer;
            this.topicId = topicId;
            this.generation = generation;
            this.audio = params.document;
            this.path = params.path;
            this.params = params;
            this.startMs = startMs;
            this.endMs = endMs;
            this.previewEligible = params.sendAnimationData != null && params.sendAnimationData.fromPreview;
            this.previewSendTime = previewSendTime;
            this.presentationListener = new WeakReference<>(listener);
            if (listener != null) listeners.put(listener, new Preview(audio, generation));
            this.key = key(account, path);
            this.draft = MediaDataController.getInstance(account).getDraftVoice(dialogId, topicId);
            this.samples = draft == null ? 0 : draft.samplesCount;
            this.frames = draft == null ? 0 : draft.writedFrame;
            this.duration = draft == null ? 0 : draft.recordTimeCount;
            this.audioId = audio.id;
            this.draftId = draft == null ? 0 : draft.id;
            submission.previews.put(audio, true);
            if (draft != null) submission.drafts.put(draft, true);
        }
    }

    private static String key(int account, String path) {
        return account + ":" + path;
    }

    public static boolean isPending(int account, String path) {
        return path != null && pending.containsKey(key(account, path));
    }

    public static boolean wasSubmitted(int account, String path) {
        submitted.values().removeIf(Submission::isUnused);
        return path != null && submitted.containsKey(key(account, path));
    }

    public static Work trackPreview(int account, String path, TLRPC.Document audio, long generation, Listener listener) {
        if (audio == null || path == null) return null;
        Work work = pending.get(key(account, path));
        Submission submission = work == null ? submitted.get(key(account, path)) : work.submission;
        if (submission != null) submission.previews.put(audio, true);
        if (work != null && listener != null) work.listeners.put(listener, new Preview(audio, generation));
        return work;
    }

    public static void cancel(int account, String path) {
        outcomes.remove(key(account, path));
        Work work = pending.get(key(account, path));
        if (work != null) work.cancelled = true;
    }

    public static Work start(int account, long topicId, long generation, SendMessagesHelper.SendMessageParams params,
                             long startMs, long endMs, long previewSendTime, Listener listener) {
        if (isPending(account, params.path) || wasSubmitted(account, params.path)) return null;
        outcomes.remove(key(account, params.path));
        if (observed.add(account)) NotificationCenter.getInstance(account).addObserver(logout, NotificationCenter.appDidLogout);
        Work work = new Work(account, topicId, generation, params, startMs, endMs, previewSendTime, listener);
        pending.put(work.key, work);
        try {
            freeze(params, account);
            if (work.draft != null && work.draftId != 0 && work.draftId != work.audioId) throw new IOException("Voice draft changed");
            if (!isCurrent(work)) throw new IOException("Voice draft no longer owned");
            work.source = Os.stat(work.path);
        } catch (Exception | OutOfMemoryError error) {
            work.error = error;
            AndroidUtilities.runOnUIThread(() -> complete(work));
            return work;
        }
        if (!QueueHolder.queue.postRunnable(() -> prepare(work))) {
            work.error = new IOException("Voice preparation queue unavailable");
            AndroidUtilities.runOnUIThread(() -> complete(work));
        }
        return work;
    }

    private static SerializedData serialize(org.telegram.tgnet.TLObject value) {
        SerializedData output = new SerializedData(value.getObjectSize());
        value.serializeToStream(output);
        byte[] bytes = output.toByteArray();
        output.cleanup();
        return new SerializedData(bytes);
    }

    private static MessageObject freezeMessage(MessageObject message, int account) {
        if (message == null) return null;
        SerializedData input = serialize(message.messageOwner);
        try {
            MessageObject copy = new MessageObject(account, TLRPC.Message.TLdeserialize(input, input.readInt32(true), true), false, false);
            copy.isTopicMainMessage = message.isTopicMainMessage;
            return copy;
        } finally {
            input.cleanup();
        }
    }

    private static void freeze(SendMessagesHelper.SendMessageParams params, int account) {
        SerializedData input = serialize(params.document);
        try {
            params.document = (TLRPC.TL_document) TLRPC.Document.TLdeserialize(input, input.readInt32(true), true);
        } finally {
            input.cleanup();
        }
        MessageObject reply = params.replyToMsg;
        params.replyToMsg = freezeMessage(reply, account);
        params.replyToTopMsg = reply != null && reply == params.replyToTopMsg ? params.replyToMsg : freezeMessage(params.replyToTopMsg, account);
        if (params.replyToStoryItem != null) {
            TL_stories.StoryItem story = params.replyToStoryItem;
            input = serialize(story);
            try {
                params.replyToStoryItem = TL_stories.StoryItem.TLdeserialize(input, input.readInt32(true), true);
                params.replyToStoryItem.dialogId = story.dialogId;
            } finally {
                input.cleanup();
            }
        }
        if (params.replyQuote != null) {
            ChatActivity.ReplyQuote quote = params.replyQuote;
            MessageObject message = freezeMessage(quote.message, account);
            params.replyQuote = quote.todo ? ChatActivity.ReplyQuote.from(message, quote.task_id)
                    : quote.poll ? ChatActivity.ReplyQuote.fromPollOption(message, quote.option_id == null ? null : quote.option_id.clone())
                    : ChatActivity.ReplyQuote.from(message, quote.start, quote.end);
        }
        if (params.suggestionParams != null) params.suggestionParams = MessageSuggestionParams.of(params.suggestionParams.toTl());
        if (params.sendMessageChatArguments != null) {
            SendMessageChatArguments arguments = params.sendMessageChatArguments;
            SendMessageChatArguments.Builder builder = new SendMessageChatArguments.Builder();
            builder.setWelcomeMessageChatId(arguments.welcomeMessageChatId);
            builder.setQuickReplyShortcut(arguments.quickReplyShortcut, arguments.quickReplyShortcutId);
            params.sendMessageChatArguments = builder.build();
        }
        params.sendAnimationData = null;
    }

    private static boolean isCurrent(Work work) {
        if (work.cancelled || !UserConfig.getInstance(work.account).isClientActivated()
                || UserConfig.getInstance(work.account).getClientUserId() != work.userId) return false;
        MediaDataController.DraftVoice current = MediaDataController.getInstance(work.account).getDraftVoice(work.dialogId, work.topicId);
        if (work.audio.id != work.audioId || (current == null) != (work.draft == null)) return false;
        if (current != null && (!work.path.equals(current.path) || current.id != work.draftId || current.samplesCount != work.samples
                || current.writedFrame != work.frames || current.recordTimeCount != work.duration)) return false;
        MediaController media = MediaController.getInstance();
        if (current == null && media.recordingAudio != work.audio) return false;
        return !work.submission.previews.containsKey(media.recordingAudio)
                || media.isRecordingPaused() && !media.isTrimmingCurrentRecording();
    }

    private static boolean ownsSource(Work work) throws Exception {
        StructStat now = Os.stat(work.path);
        return now.st_dev == work.source.st_dev && now.st_ino == work.source.st_ino
                && now.st_size == work.source.st_size && now.st_mtime == work.source.st_mtime
                && now.st_ctime == work.source.st_ctime;
    }

    private static void prepare(Work work) {
        try {
            if (!work.cancelled) {
                if (!ownsSource(work)) throw new IOException("Voice source changed");
                File source = new File(work.path).getAbsoluteFile();
                work.result = File.createTempFile("voice-send-", ".ogg", source.getParentFile());
                if (work.startMs < 0 || work.endMs <= work.startMs
                        || !MediaController.cropOpusFile(work.path, work.result.getAbsolutePath(), work.startMs, work.endMs)
                        || work.result.length() == 0) throw new IOException("Unable to prepare voice recording");
                work.params.document.size = work.result.length();
                byte[] waveform = MediaController.getWaveform(work.result.getAbsolutePath());
                for (TLRPC.DocumentAttribute attribute : work.params.document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                        attribute.waveform = waveform;
                        attribute.duration = (work.endMs - work.startMs) / 1000.0;
                        if (waveform == null) attribute.flags &= ~4;
                        else attribute.flags |= 4;
                    }
                }
            }
        } catch (Exception | OutOfMemoryError error) {
            work.error = error;
        }
        AndroidUtilities.runOnUIThread(() -> complete(work));
    }

    private static void complete(Work work) {
        if (pending.get(work.key) != work) return;
        boolean sent = false;
        try {
            if (isCurrent(work) && work.error == null && work.result != null) {
                if (!ownsSource(work)) throw new IOException("Voice source changed");
                work.params.path = work.result.getAbsolutePath();
                MediaDataController.DraftVoice current = MediaDataController.getInstance(work.account).getDraftVoice(work.dialogId, work.topicId);
                MediaController media = MediaController.getInstance();
                TLRPC.Document recording = media.recordingAudio;
                boolean ownsRecording = recording != null && recording.id == work.audioId
                        && work.submission.previews.containsKey(recording)
                        && media.isRecordingPaused() && !media.isTrimmingCurrentRecording();
                if (current != null) work.submission.drafts.put(current, true);
                submitted.put(work.key, work.submission);
                Listener presentation = work.presentationListener.get();
                if (presentation != null && work.canPresentPreview(presentation, System.currentTimeMillis())) {
                    try {
                        work.params.sendAnimationData = presentation.getVoiceSendAnimation(work);
                    } catch (Exception error) {
                        FileLog.e(error);
                    }
                }
                SendMessagesHelper.PreparedVoiceSendOutcome outcome = SendMessagesHelper.getInstance(work.account).sendPreparedVoiceMessage(work.params);
                if (outcome != SendMessagesHelper.PreparedVoiceSendOutcome.ACCEPTED) {
                    submitted.remove(work.key);
                    if (outcome == SendMessagesHelper.PreparedVoiceSendOutcome.PLUGIN_CANCELLED
                            || outcome == SendMessagesHelper.PreparedVoiceSendOutcome.PLUGIN_OWNED) {
                        work.cancelled = true;
                        if (outcome == SendMessagesHelper.PreparedVoiceSendOutcome.PLUGIN_OWNED) work.result = null;
                    } else {
                        work.needsConfirmation = outcome == SendMessagesHelper.PreparedVoiceSendOutcome.NEEDS_CONFIRMATION;
                        throw new IOException("Voice send was not accepted: " + outcome);
                    }
                } else {
                    sent = true;
                    work.result = null;
                    if (isCurrent(work) && MediaDataController.getInstance(work.account).getDraftVoice(work.dialogId, work.topicId) == current) {
                        if (ownsRecording && media.recordingAudio == recording
                                && media.isRecordingPaused() && !media.isTrimmingCurrentRecording()) {
                            media.cleanRecording(false);
                        }
                        if (current != null) MediaDataController.getInstance(work.account).pushDraftVoiceMessage(work.dialogId, work.topicId, null);
                    }
                }
            }
        } catch (Exception error) {
            work.error = error;
            if (!sent) submitted.remove(work.key);
        } finally {
            pending.remove(work.key);
            if (work.result != null) {
                File result = work.result;
                work.result = null;
                if (!QueueHolder.queue.postRunnable(result::delete)) result.delete();
            }
        }
        if (work.error != null) FileLog.e(work.error);
        if (work.failed() && isCurrent(work)) {
            outcomes.put(work.key, new Outcome(work));
            while (outcomes.size() > 32) outcomes.remove(outcomes.keySet().iterator().next());
        }
        for (Listener listener : new ArrayList<>(work.listeners.keySet())) {
            try {
                listener.onVoiceSendPrepared(work, sent);
            } catch (Exception error) {
                FileLog.e(error);
            }
        }
    }
}
