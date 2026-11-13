/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger.voip;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.XiaomiUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.VoIPFragment;
import org.telegram.ui.VoIPPermissionActivity;

import java.util.ArrayList;

import java.util.UUID;
import java.util.WeakHashMap;
public class VoIPPreNotificationService { // } extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final class IncomingCall {
        final int account;
        final long ownerId, ownerSession, callId, userId, generation;
        final Intent intent;
        TL_phone.PhoneCall call;

        private IncomingCall(Intent intent, TL_phone.PhoneCall call, long generation) {
            this.intent = intent;
            this.call = call;
            this.generation = generation;
            account = intent.getIntExtra("account", -1);
            ownerId = UserConfig.getInstance(account).getClientUserId();
            ownerSession = app.nimarkogram.messenger.notifications.InAppNotifications.session(account);
            callId = call.id;
            userId = intent.getLongExtra("user_id", 0);
        }

        private boolean isAccountCurrent() {
            return app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, ownerId, ownerSession);
        }

        private boolean matches(Intent action) {
            return action != null && processOwner.equals(action.getStringExtra(EXTRA_PROCESS))
                    && action.getLongExtra(EXTRA_LAUNCH, -1) == generation
                    && action.getIntExtra("account", -1) == account
                    && (!action.hasExtra("currentAccount") || action.getIntExtra("currentAccount", -1) == account)
                    && action.getLongExtra(EXTRA_OWNER, 0) == ownerId
                    && action.getLongExtra(EXTRA_SESSION, -1) == ownerSession
                    && action.getLongExtra("call_id", 0) == callId
                    && action.getLongExtra("user_id", 0) == userId
                    && !action.getBooleanExtra("is_outgoing", false)
                    && action.getLongExtra("chat_id", 0) == 0
                    && action.getByteArrayExtra("joinConference") == null
                    && action.getByteArrayExtra("joinConferenceCall") == null
                    && !action.getBooleanExtra("createGroupCall", false);
        }
    }

    private static final class ServiceOwner {
        final int account;
        final long ownerId, ownerSession, generation, incomingGeneration;
        long callId, groupId, scope;
        int kind;
        boolean retired;

        private ServiceOwner(int account, long ownerId, long ownerSession, long generation, int kind, IncomingCall incoming) {
            this.account = account;
            this.ownerId = ownerId;
            this.ownerSession = ownerSession;
            this.generation = generation;
            this.kind = kind;
            incomingGeneration = incoming == null ? 0 : incoming.generation;
            callId = incoming == null ? 0 : incoming.callId;
        }

        private boolean isAccountCurrent() {
            return app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, ownerId, ownerSession);
        }

        private void update(VoIPService service) {
            int nextKind = kind;
            if (service.isConference()) nextKind = 2;
            else if (service.getGroupCall() != null) nextKind = 1;
            else if (service.getPrivateCall() != null) nextKind = 0;
            final long call = nextKind == 0 ? service.getCallID() : 0;
            final long group = nextKind == 0 ? 0 : service.getGroupCallID();
            if (nextKind != kind || callId != 0 && callId != call || groupId != 0 && groupId != group) scope++;
            kind = nextKind;
            callId = call;
            groupId = group;
        }
    }
    public static final class State implements VoIPServiceState {

        private final int currentAccount;
        private final long userId;
        private final TL_phone.PhoneCall call;
        private final long callId, generation, ownerId, ownerSession;
        private final Intent intent;
        private final IncomingCall launch;
        private long serviceOwner;
        private boolean destroyed;

        public State(int currentAccount, long userId, TL_phone.PhoneCall phoneCall) {
            this.currentAccount = currentAccount;
            this.userId = userId;
            this.call = phoneCall;
            callId = phoneCall == null ? 0 : phoneCall.id;
            generation = pendingGeneration;
            intent = pendingVoIP;
            launch = incomingCall != null && incomingCall.intent == intent && incomingCall.account == currentAccount
                    && incomingCall.callId == callId ? incomingCall : null;
            ownerId = launch == null ? 0 : launch.ownerId;
            ownerSession = launch == null ? -1 : launch.ownerSession;
        }

        private boolean isAccountCurrent() {
            return app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(currentAccount, ownerId, ownerSession);
        }

        private boolean isPendingOwner() {
            return !destroyed && generation == pendingGeneration && currentState == this
                    && intent != null && pendingVoIP == intent
                    && call != null && call.id == callId && pendingCall != null && pendingCall.id == callId
                    && intent.getIntExtra("account", -1) == currentAccount
                    && intent.getLongExtra("user_id", 0) == userId && ownsIncomingCall(launch, intent);
        }

        private boolean isCurrent() {
            return isPendingOwner() && isAccountCurrent();
        }

        @Override
        public TLRPC.User getUser() {
            return isAccountCurrent() ? MessagesController.getInstance(currentAccount).getUser(userId) : null;
        }

        @Override
        public boolean isOutgoing() {
            return false;
        }

        @Override
        public int getCallState() {
            return isCurrent() ? VoIPService.STATE_WAITING_INCOMING : VoIPService.STATE_ENDED;
        }

        @Override
        public TL_phone.PhoneCall getPrivateCall() {
            return call;
        }

        @Override
        public boolean isCallingVideo() {
            if (call != null) {
                return call.video;
            }
            return false;
        }

        @Override
        public void acceptIncomingCall() {
            if (isCurrent()) answer(ApplicationLoader.applicationContext);
        }

        @Override
        public void declineIncomingCall() {
            if (isCurrent()) decline(ApplicationLoader.applicationContext, VoIPService.DISCARD_REASON_HANGUP);
        }

        @Override
        public void stopRinging() {
            if (isCurrent()) VoIPPreNotificationService.stopRinging();
        }

        public void destroy() {
            if (destroyed) return;
            destroyed = true;
            if (currentState == this && VoIPService.getSharedState() == this && VoIPFragment.getInstance() != null) {
                VoIPFragment.getInstance().onStateChanged(getCallState());
            }
        }

        @Override
        public boolean isConference() {
            return false;
        }

        @Override
        public TLRPC.GroupCall getGroupCall() {
            return null;
        }

        @Override
        public ArrayList<TLRPC.GroupCallParticipant> getGroupParticipants() {
            return null;
        }
    }

    public static State getState() {
        return currentState != null && (currentState.destroyed || currentState.isCurrent()) ? currentState : null;
    }

//    public static VoIPPreNotificationService instance;

    private static Notification makeNotification(Context context, State state) {
        if (Build.VERSION.SDK_INT < 33) return null;

        final int account = state.currentAccount;
        final long user_id = state.userId;
        final long call_id = state.callId;
        final boolean video = state.call.video;
        final TLRPC.User user = MessagesController.getInstance(account).getUser(user_id);

        final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        final Intent intent = bindOwner(new Intent(context, LaunchActivity.class).setAction("voip"), state);
        final Notification.Builder builder = new Notification.Builder(context)
            .setContentTitle(LocaleController.getString(video ? R.string.VoipInVideoCallBranding : R.string.VoipInCallBranding))
            .setSmallIcon(R.drawable.call)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT
                )
            );

        SharedPreferences nprefs = MessagesController.getGlobalNotificationsSettings();
        int chanIndex = nprefs.getInt("calls_notification_channel", 0);
        NotificationChannel oldChannel = nm.getNotificationChannel("incoming_calls2" + chanIndex);
        if (oldChannel != null) {
            nm.deleteNotificationChannel(oldChannel.getId());
        }
        oldChannel = nm.getNotificationChannel("incoming_calls3" + chanIndex);
        if (oldChannel != null) {
            nm.deleteNotificationChannel(oldChannel.getId());
        }
        NotificationChannel existingChannel = nm.getNotificationChannel("incoming_calls4" + chanIndex);
        boolean needCreate = true;
        if (existingChannel != null) {
            if (existingChannel.getImportance() < NotificationManager.IMPORTANCE_HIGH || existingChannel.getSound() != null) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("User messed up the notification channel; deleting it and creating a proper one");
                }
                nm.deleteNotificationChannel("incoming_calls4" + chanIndex);
                chanIndex++;
                nprefs.edit().putInt("calls_notification_channel", chanIndex).commit();
            } else {
                needCreate = false;
            }
        }
        if (needCreate) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build();
            NotificationChannel chan = new NotificationChannel("incoming_calls4" + chanIndex, LocaleController.getString(R.string.IncomingCallsSystemSetting), NotificationManager.IMPORTANCE_HIGH);
            try {
                chan.setSound(null, attrs);
            } catch (Exception e) {
                FileLog.e(e);
            }
            chan.setDescription(LocaleController.getString(R.string.IncomingCallsSystemSettingDescription));
            chan.enableVibration(false);
            chan.enableLights(false);
            chan.setBypassDnd(true);
            try {
                nm.createNotificationChannel(chan);
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
        }
        builder.setChannelId("incoming_calls4" + chanIndex);

        final Intent endIntent = bindOwner(new Intent(context, VoIPActionsReceiver.class), state);
        endIntent.setAction(context.getPackageName() + ".DECLINE_CALL");
        endIntent.putExtra("call_id", call_id);
        CharSequence endTitle = LocaleController.getString(R.string.VoipDeclineCall);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            endTitle = new SpannableString(endTitle);
            ((SpannableString) endTitle).setSpan(new ForegroundColorSpan(0xFFF44336), 0, endTitle.length(), 0);
        }
        final PendingIntent endPendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                endIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT
            );

        final Intent answerIntent = bindOwner(new Intent(context, VoIPActionsReceiver.class), state);
        answerIntent.setAction(context.getPackageName() + ".ANSWER_CALL");
        answerIntent.putExtra("call_id", call_id);
        CharSequence answerTitle = LocaleController.getString(R.string.VoipAnswerCall);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            answerTitle = new SpannableString(answerTitle);
            ((SpannableString) answerTitle).setSpan(new ForegroundColorSpan(0xFF00AA00), 0, answerTitle.length(), 0);
        }
        final PendingIntent answerPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                bindOwner(new Intent(context, LaunchActivity.class).setAction("voip_answer"), state).putExtra(LaunchActivity.EXTRA_VOIP_ANSWER_TOKEN, LaunchActivity.VOIP_ANSWER_TOKEN),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT
            );

        builder.setPriority(Notification.PRIORITY_MAX);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            builder.setShowWhen(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(0xff2ca5e0);
            builder.setVibrate(new long[0]);
            builder.setCategory(Notification.CATEGORY_CALL);
            builder.setFullScreenIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_MUTABLE), true);
            if (user != null && !TextUtils.isEmpty(user.phone)) {
                builder.addPerson("tel:" + user.phone);
            }
        }

        final Intent hideIntent = bindOwner(new Intent(ApplicationLoader.applicationContext, VoIPActionsReceiver.class), state);
        hideIntent.setAction(context.getPackageName() + ".HIDE_CALL");
        final PendingIntent hidePendingIntent =
            PendingIntent.getBroadcast(
                ApplicationLoader.applicationContext,
                0,
                hideIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        builder.setDeleteIntent(hidePendingIntent);

        Bitmap avatar = VoIPService.getRoundAvatarBitmap(context, account, user);
        String personName = ContactsController.formatName(user);
        if (TextUtils.isEmpty(personName)) {
            //java.lang.IllegalArgumentException: person must have a non-empty a name
            personName = "___";
        }
        Person person = new Person.Builder()
                .setName(personName)
                .setIcon(Icon.createWithAdaptiveBitmap(avatar)).build();
        Notification.CallStyle notificationStyle = Notification.CallStyle.forIncomingCall(person, endPendingIntent, answerPendingIntent);

        builder.setStyle(notificationStyle);
        return builder.build();
    }

    private static final Object sync = new Object();
    private static MediaPlayer ringtonePlayer;
    private static Vibrator vibrator;

    public static void startRinging(Context context, int account, long user_id) {
        SharedPreferences prefs = MessagesController.getNotificationsSettings(account);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean needRing = am.getRingerMode() != AudioManager.RINGER_MODE_SILENT;
        final boolean isHeadsetPlugged = am.isWiredHeadsetOn();
        if (needRing) {
            if (ringtonePlayer != null) {
                return;
            }
            synchronized (sync) {
                if (ringtonePlayer != null) {
                    return;
                }
                ringtonePlayer = new MediaPlayer();
                ringtonePlayer.setOnPreparedListener(mediaPlayer -> {
                    try {
                        ringtonePlayer.start();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
                ringtonePlayer.setLooping(true);
                if (isHeadsetPlugged) {
                    ringtonePlayer.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);
                } else {
                    ringtonePlayer.setAudioStreamType(AudioManager.STREAM_RING);
                }
                try {
                    String notificationUri;
                    if (prefs.getBoolean("custom_" + user_id, false)) {
                        notificationUri = prefs.getString("ringtone_path_" + user_id, null);
                    } else {
                        notificationUri = prefs.getString("CallsRingtonePath", null);
                    }
                    Uri ringtoneUri;
                    boolean isDafaultUri = false;
                    if (notificationUri == null) {
                        ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                        isDafaultUri = true;
                    } else {
                        Uri defaultUri = Settings.System.DEFAULT_RINGTONE_URI;
                        if (defaultUri != null && notificationUri.equalsIgnoreCase(defaultUri.getPath())) {
                            ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                            isDafaultUri = true;
                        } else {
                            ringtoneUri = Uri.parse(notificationUri);
                        }
                    }
                    FileLog.d("start ringtone with " + isDafaultUri + " " + ringtoneUri);
                    ringtonePlayer.setDataSource(context, ringtoneUri);
                    ringtonePlayer.prepareAsync();
                } catch (Exception e) {
                    FileLog.e(e);
                    if (ringtonePlayer != null) {
                        ringtonePlayer.release();
                        ringtonePlayer = null;
                    }
                }
                int vibrate;
                if (prefs.getBoolean("custom_" + user_id, false)) {
                    vibrate = prefs.getInt("calls_vibrate_" + user_id, 0);
                } else {
                    vibrate = prefs.getInt("vibrate_calls", 0);
                }
                if ((vibrate != 2 && vibrate != 4 && (am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL)) || (vibrate == 4 && am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE)) {
                    vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    long duration = 700;
                    if (vibrate == 1) {
                        duration /= 2;
                    } else if (vibrate == 3) {
                        duration *= 2;
                    }
                    long[] pattern = new long[]{0, duration, 500};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        vibrator.vibrate(
                                VibrationEffect.createWaveform(pattern, 0),
                                new VibrationAttributes.Builder()
                                    .setUsage(VibrationAttributes.USAGE_RINGTONE) // required for background apps
                                    .build());
                    } else {
                        vibrator.vibrate(pattern, 0);
                    }
                }
            }
        }
    }

    public static void stopRinging() {
        synchronized (sync) {
            if (ringtonePlayer != null) {
                ringtonePlayer.stop();
                ringtonePlayer.release();
                ringtonePlayer = null;
            }
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

//    public void onAudioFocusChange(int focusChange) {
//        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
//            hasAudioFocus = true;
//        } else {
//            hasAudioFocus = false;
//        }
//    }

    public static TL_phone.PhoneCall pendingCall;
    public static Intent pendingVoIP;
    public static State currentState;
//    public static Intent pendingNotificationService;

    private static long pendingGeneration;
    private static final String EXTRA_GENERATION = "voip_pre_notification_generation";
    private static final String EXTRA_OWNER = "voip_pre_notification_owner";
    private static final String EXTRA_SESSION = "voip_pre_notification_session";
    private static final String EXTRA_PROCESS = "voip_call_process";
    private static final String EXTRA_SERVICE = "voip_call_service_owner";
    private static final String EXTRA_GROUP = "voip_call_group_id";
    private static final String EXTRA_SCOPE = "voip_call_scope";
    private static final String EXTRA_KIND = "voip_call_kind";
    private static final String EXTRA_INITIAL = "voip_call_initial_ids";
    private static final String EXTRA_LAUNCH = "voip_incoming_launch";
    private static final String processOwner = UUID.randomUUID().toString();
    private static final WeakHashMap<VoIPService, ServiceOwner> serviceOwners = new WeakHashMap<>();
    private static long serviceGeneration;
    private static State handedOffState;
    private static IncomingCall incomingCall;
    private static long incomingGeneration;


    private static long getServiceOwner(VoIPService service) {
        final ServiceOwner owner = serviceOwners.get(service);
        return owner == null ? 0 : owner.generation;
    }

    public static boolean admitServiceOwner(VoIPService service, Intent intent, int account, long ownerId, long ownerSession, IncomingCall incoming) {
        if (serviceOwners.containsKey(service)
                || !app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, ownerId, ownerSession)) return false;
        final int kind = intent.getByteArrayExtra("joinConference") != null ? 2 : intent.getLongExtra("chat_id", 0) != 0 ? 1 : 0;
        serviceOwners.put(service, new ServiceOwner(account, ownerId, ownerSession, ++serviceGeneration, kind, incoming));
        return true;
    }

    public static void retireServiceOwner(VoIPService service) {
        final ServiceOwner owner = serviceOwners.get(service);
        if (owner != null) owner.retired = true;
    }

    public static void abandonServiceOwner(VoIPService service) {
        if (VoIPService.getSharedInstance() != service) serviceOwners.remove(service);
    }

    public static boolean isServiceOwnerCurrent(VoIPService service) {
        final ServiceOwner owner = serviceOwners.get(service);
        return owner != null && !owner.retired && owner.account == service.getAccount() && owner.isAccountCurrent();
    }

    private static synchronized boolean ownsIncomingCall(IncomingCall owner, Intent intent) {
        return owner != null && incomingCall == owner && VoIPService.callIShouldHavePutIntoIntent == owner.call
                && owner.matches(intent) && owner.matches(owner.intent);
    }

    private static synchronized boolean clearIncomingCall(IncomingCall owner) {
        if (owner == null || incomingCall != owner) return false;
        incomingCall = null;
        if (VoIPService.callIShouldHavePutIntoIntent == owner.call) VoIPService.callIShouldHavePutIntoIntent = null;
        return true;
    }

    public static synchronized boolean cancelIncomingCall(Intent intent) {
        final IncomingCall owner = incomingCall;
        if (owner == null || !owner.matches(intent)) return false;
        final State state = currentState;
        if (state != null && state.launch == owner && state.isPendingOwner()) {
            dismiss(ApplicationLoader.applicationContext, false);
            return true;
        }
        if (!clearIncomingCall(owner)) return false;
        rejectIncomingCall(owner);
        return true;
    }

    public static synchronized void rejectIncomingCall(IncomingCall owner) {
        if (owner == null) return;
        final VoIPService service = VoIPService.getSharedInstance();
        if (owner.isAccountCurrent() && (incomingCall == null || incomingCall.account != owner.account)
                && (service == null || service.getAccount() != owner.account)) {
            MessagesController.getInstance(owner.account).ignoreSetOnline = false;
        }
        if (handedOffState != null && handedOffState.launch == owner) {
            final State handedOff = handedOffState;
            handedOffState = null;
            if (currentState == null && pendingVoIP == null && incomingCall == null && VoIPService.getSharedInstance() == null) {
                currentState = handedOff;
                dismiss(ApplicationLoader.applicationContext, false, false);
            }
        }
    }

    public static synchronized boolean hasPendingIncomingCall() {
        if (incomingCall != null && !incomingCall.isAccountCurrent()) cancelIncomingCall(incomingCall.intent);
        return VoIPService.callIShouldHavePutIntoIntent != null;
    }

    public static synchronized boolean prepareIncomingCall(Intent intent, TL_phone.PhoneCall call) {
        if (intent == null || call == null || call instanceof TL_phone.TL_phoneCallDiscarded
                || !UserConfig.isValidAccount(intent.getIntExtra("account", -1)) || hasPendingIncomingCall()) return false;
        final IncomingCall owner = new IncomingCall(intent, call, ++incomingGeneration);
        if (!owner.isAccountCurrent()) return false;
        intent.putExtra(EXTRA_LAUNCH, owner.generation).putExtra(EXTRA_OWNER, owner.ownerId)
                .putExtra(EXTRA_SESSION, owner.ownerSession).putExtra(EXTRA_PROCESS, processOwner)
                .putExtra("call_id", owner.callId).putExtra("currentAccount", owner.account);
        incomingCall = owner;
        VoIPService.callIShouldHavePutIntoIntent = call;
        return true;
    }

    public static synchronized IncomingCall getIncomingCall(Intent intent) {
        final IncomingCall owner = incomingCall;
        return ownsIncomingCall(owner, intent) && owner.isAccountCurrent()
                && !(owner.call instanceof TL_phone.TL_phoneCallDiscarded) ? owner : null;
    }

    public static boolean hasIncomingCallOwner(Intent intent) {
        return intent != null && intent.hasExtra(EXTRA_LAUNCH);
    }

    public static synchronized IncomingCall consumeIncomingCall(Intent intent, VoIPService service) {
        final IncomingCall owner = getIncomingCall(intent);
        if (owner == null || !admitServiceOwner(service, intent, owner.account, owner.ownerId, owner.ownerSession, owner)) return null;
        return clearIncomingCall(owner) ? owner : null;
    }

    public static synchronized boolean updateIncomingCall(int account, TL_phone.PhoneCall call) {
        final IncomingCall owner = incomingCall;
        if (owner == null || call == null || owner.account != account || owner.callId != call.id
                || VoIPService.callIShouldHavePutIntoIntent != owner.call) return false;
        if (call instanceof TL_phone.TL_phoneCallDiscarded) return cancelIncomingCall(owner.intent);
        if (!owner.isAccountCurrent()) return cancelIncomingCall(owner.intent);
        owner.call = call;
        VoIPService.callIShouldHavePutIntoIntent = call;
        return true;
    }

    private static Intent setOwnerData(Intent action) {
        return action.setData(new Uri.Builder().scheme("tgvoip").authority("call-owner")
                .appendPath(processOwner).appendPath(Integer.toString(action.getIntExtra("account", -1)))
                .appendPath(Long.toString(action.getLongExtra("call_id", 0)))
                .appendPath(Long.toString(action.getLongExtra(EXTRA_GROUP, 0)))
                .appendPath(action.hasExtra(EXTRA_SERVICE) ? "service" : "pending")
                .appendPath(Long.toString(action.getLongExtra(EXTRA_SERVICE, action.getLongExtra(EXTRA_GENERATION, -1))))
                .appendPath(Long.toString(action.getLongExtra(EXTRA_SCOPE, 0)))
                .appendPath(Integer.toString(action.getIntExtra(EXTRA_KIND, 0)))
                .build());
    }

    private static Intent bindOwner(Intent action, State state) {
        action.removeExtra(EXTRA_SERVICE);
        action.removeExtra(EXTRA_GROUP);
        action.removeExtra(EXTRA_SCOPE);
        action.removeExtra(EXTRA_KIND);
        action.removeExtra(EXTRA_INITIAL);
        return setOwnerData(action.putExtra("account", state.currentAccount).putExtra("currentAccount", state.currentAccount)
                .putExtra("call_id", state.callId).putExtra("video", state.call.video)
                .putExtra(EXTRA_GENERATION, state.generation).putExtra(EXTRA_OWNER, state.ownerId)
                .putExtra(EXTRA_SESSION, state.ownerSession).putExtra(EXTRA_PROCESS, processOwner));
    }

    public static Intent bindOwner(Intent action, VoIPService service) {
        final ServiceOwner owner = serviceOwners.get(service);
        if (owner == null) return null;
        owner.update(service);
        action.removeExtra(EXTRA_GENERATION);
        action.removeExtra(EXTRA_LAUNCH);
        return setOwnerData(action.putExtra("account", owner.account).putExtra("currentAccount", owner.account)
                .putExtra("call_id", owner.callId).putExtra("video", service.isCallingVideo())
                .putExtra(EXTRA_GROUP, owner.groupId).putExtra(EXTRA_SERVICE, owner.generation)
                .putExtra(EXTRA_SCOPE, owner.scope).putExtra(EXTRA_KIND, owner.kind)
                .putExtra(EXTRA_INITIAL, (owner.callId == 0 ? 1 : 0) | (owner.groupId == 0 ? 2 : 0))
                .putExtra(EXTRA_OWNER, owner.ownerId).putExtra(EXTRA_SESSION, owner.ownerSession)
                .putExtra(EXTRA_PROCESS, processOwner));
    }

    public static boolean hasCallOwner(Intent action) {
        return action != null && (action.hasExtra(EXTRA_GENERATION) || action.hasExtra(EXTRA_SERVICE)
                || action.hasExtra(EXTRA_OWNER) || action.hasExtra(EXTRA_SESSION) || action.hasExtra(EXTRA_PROCESS));
    }

    private static boolean isAccountCurrent(Intent action) {
        if (action == null || !processOwner.equals(action.getStringExtra(EXTRA_PROCESS))) return false;
        final int account = action.getIntExtra("account", -1);
        return (!action.hasExtra("currentAccount") || action.getIntExtra("currentAccount", -1) == account)
                && app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account,
                    action.getLongExtra(EXTRA_OWNER, 0), action.getLongExtra(EXTRA_SESSION, -1));
    }

    private static boolean matchesOwner(State state, Intent action) {
        return state != null && isAccountCurrent(action) && state.isAccountCurrent()
                && !action.hasExtra(EXTRA_SERVICE) && action.getLongExtra(EXTRA_GENERATION, -1) == state.generation
                && action.getIntExtra("account", -1) == state.currentAccount
                && action.getLongExtra("call_id", 0) == state.callId
                && action.getLongExtra(EXTRA_OWNER, 0) == state.ownerId
                && action.getLongExtra(EXTRA_SESSION, -1) == state.ownerSession;
    }

    private static State getPendingState(Intent action) {
        final State state = currentState;
        return state != null && state.isCurrent() && matchesOwner(state, action) ? state : null;
    }

    private static VoIPService getActionService(Intent action) {
        if (!isAccountCurrent(action)) return null;
        final VoIPService service = VoIPService.getSharedInstance();
        if (service == null || service.getAccount() != action.getIntExtra("account", -1)) return null;
        final ServiceOwner admitted = serviceOwners.get(service);
        if (admitted == null || admitted.retired || !admitted.isAccountCurrent()
                || action.getLongExtra(EXTRA_OWNER, 0) != admitted.ownerId
                || action.getLongExtra(EXTRA_SESSION, -1) != admitted.ownerSession) return null;
        admitted.update(service);
        final int callState = service.getCallState();
        if (callState == VoIPService.STATE_ENDED || callState == VoIPService.STATE_FAILED
                || callState == VoIPService.STATE_HANGING_UP || callState == VoIPService.STATE_BUSY) return null;
        if (action.hasExtra(EXTRA_SERVICE)) {
            final int initial = action.getIntExtra(EXTRA_INITIAL, 0);
            return action.getLongExtra(EXTRA_SERVICE, 0) == admitted.generation
                    && action.getLongExtra(EXTRA_SCOPE, -1) == admitted.scope
                    && action.getIntExtra(EXTRA_KIND, -1) == admitted.kind
                    && (action.getLongExtra("call_id", 0) == admitted.callId || (initial & 1) != 0 && action.getLongExtra("call_id", 0) == 0)
                    && (action.getLongExtra(EXTRA_GROUP, 0) == admitted.groupId || (initial & 2) != 0 && action.getLongExtra(EXTRA_GROUP, 0) == 0) ? service : null;
        }
        State state = getPendingState(action);
        if (state == null && matchesOwner(handedOffState, action)) state = handedOffState;
        if (state == null || state.launch == null || !matchesService(state, service)
                || admitted.incomingGeneration != state.launch.generation || admitted.scope != 0 || admitted.kind != 0) return null;
        final long owner = getServiceOwner(service);
        if (state.serviceOwner == 0) state.serviceOwner = owner;
        return state.serviceOwner == owner ? service : null;
    }

    public static boolean isCurrentCallAction(Intent action) {
        if (!isAccountCurrent(action)) return false;
        if (getActionService(action) != null) return true;
        if (VoIPService.getSharedInstance() != null) return false;
        final State state = getPendingState(action);
        if (state != null) return VoIPService.getSharedState() == state;
        return matchesOwner(handedOffState, action) && VoIPService.getSharedState() == null && hasPendingCall(handedOffState);
    }

    public static boolean isCurrentServiceAction(VoIPService service, Intent action) {
        return service != null && getActionService(action) == service;
    }

    public static Intent capturePermissionOwner(Intent action) {
        return hasCallOwner(action) && isCurrentCallAction(action) ? new Intent(action) : null;
    }

    private static State getPendingState(Context context) {
        final State state = currentState;
        if (state == null || !state.isCurrent()) return null;
        if (context instanceof VoIPPermissionActivity) {
            return getPendingState(((VoIPPermissionActivity) context).getIntent());
        }
        return state;
    }

    private static boolean checkPendingState(Context context, State state) {
        if (state.isCurrent()) return true;
        if (state.isPendingOwner()) dismiss(context, false);
        return false;
    }

    private static boolean matchesService(State state, VoIPService service) {
        final ServiceOwner owner = serviceOwners.get(service);
        if (owner == null || owner.retired || state.launch == null) return false;
        owner.update(service);
        return owner.kind == 0 && owner.scope == 0 && owner.incomingGeneration == state.launch.generation
                && owner.ownerId == state.ownerId && owner.ownerSession == state.ownerSession
                && owner.isAccountCurrent() && state.isAccountCurrent()
                && service.getAccount() == state.currentAccount && service.getCallID() == state.callId;
    }

    private static boolean hasPendingCall(State state) {
        return state != null && state.launch != null && getIncomingCall(state.intent) == state.launch;
    }
    public static void show(Context context, Intent intent, TL_phone.PhoneCall call) {
        FileLog.d("VoIPPreNotification.show()");

        if (call == null || intent == null) {
            dismiss(context, false);
            FileLog.d("VoIPPreNotification.show(): call or intent is null");
            return;
        }

        final int account = intent.getIntExtra("account", -1);
        final IncomingCall launch = getIncomingCall(intent);
        if (launch == null || launch.intent != intent || launch.account != account || launch.callId != call.id) return;
        if (currentState != null && currentState.isCurrent()
                && currentState.currentAccount == account && currentState.callId == call.id) {
            return;
        }

        dismiss(context, false, false);

        pendingVoIP = intent;
        pendingCall = call;

        handedOffState = null;
        final long user_id = intent.getLongExtra("user_id", 0);
        final State state = new State(account, user_id, call);
        currentState = state;

        acknowledge(context, state, () -> {
            if (!checkPendingState(context, state)) return;
            final Notification notification = makeNotification(context, state);
            if (!checkPendingState(context, state)) return;
            if (notification == null) {
                dismiss(context, false);
                return;
            }

            final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(VoIPService.ID_INCOMING_CALL_PRENOTIFICATION, notification);
            if (state.isCurrent()) startRinging(context, account, user_id);
        });
    }

    private static void acknowledge(Context context, State state, Runnable whenAcknowledged) {
        if (!checkPendingState(context, state)) return;
        final TL_phone.PhoneCall call = state.call;
        if (call instanceof TL_phone.TL_phoneCallDiscarded) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.w("Call " + call.id + " was discarded before the voip pre notification started, stopping");
            }
            dismiss(context, false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 19 && XiaomiUtilities.isMIUI() && !XiaomiUtilities.isCustomPermissionGranted(XiaomiUtilities.OP_SHOW_WHEN_LOCKED)) {
            if (((KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE)).inKeyguardRestrictedInputMode()) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("MIUI: no permission to show when locked but the screen is locked. ¯\\_(ツ)_/¯");
                }
                dismiss(context, false);
                return;
            }
        }
        final TL_phone.receivedCall req = new TL_phone.receivedCall();
        req.peer = new TLRPC.TL_inputPhoneCall();
        req.peer.id = state.callId;
        req.peer.access_hash = call.access_hash;
        ConnectionsManager.getInstance(state.currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.w("(VoIPPreNotification) receivedCall response = " + response);
            }
            if (!checkPendingState(context, state)) return;
            if (error != null) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("error on receivedCall: " + error);
                }
                dismiss(context, false);
            } else if (whenAcknowledged != null) {
                whenAcknowledged.run();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public static boolean open(Context context) {
        final State state = getPendingState(context);
        if (state == null) return false;
        final VoIPService service = VoIPService.getSharedInstance();
        if (service != null) {
            if (!matchesService(state, service)) return false;
            state.serviceOwner = getServiceOwner(service);
            handOff(context, state);
            return true;
        }
        return startPendingService(context, state, false);
    }

    private static void handOff(Context context, State state) {
        handedOffState = state;
        currentState = null;
        dismiss(context, true);
    }

    private static boolean startPendingService(Context context, State state, boolean accept) {
        if (!state.isCurrent() || !hasPendingCall(state)) return false;
        final Intent intent = bindOwner(state.intent, state);
        intent.putExtra("openFragment", true);
        intent.putExtra("accept", accept);
        handOff(context, state);
        try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
        } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException e) {
            FileLog.e("Error starting incoming call service", e);
            if (clearIncomingCall(state.launch) && handedOffState == state && currentState == null
                    && pendingVoIP == null && VoIPService.getSharedInstance() == null) {
                handedOffState = null;
                if (state.isAccountCurrent()) MessagesController.getInstance(state.currentAccount).ignoreSetOnline = false;
                currentState = state;
                state.destroy();
            }
            return false;
        }
    }

    public static boolean isVideo() {
        return currentState != null && currentState.isCurrent() && currentState.call.video;
    }

    public static boolean isVideo(Intent owner) {
        if (!isCurrentCallAction(owner)) return false;
        final VoIPService service = getActionService(owner);
        if (service != null) return service.isCallingVideo();
        final State state = getPendingState(owner);
        return state != null ? state.call.video : handedOffState != null && handedOffState.call.video;
    }

    private static boolean requestPermissions(Context context, Intent owner) {
        try {
            final Intent permission = new Intent(owner).setClass(context, VoIPPermissionActivity.class)
                    .setAction(null).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent.getActivity(context, 0, permission, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT).send();
            return true;
        } catch (Exception e) {
            FileLog.e("Error starting permission activity", e);
            return false;
        }
    }

    public static void answer(Context context) {
        FileLog.d("VoIPPreNotification.answer()");
        final State state = getPendingState(context);
        if (state != null) answer(context, bindOwner(new Intent(), state));
    }

    public static boolean answer(Context context, Intent owner) {
        if (!isCurrentCallAction(owner)) return false;
        final VoIPService service = getActionService(owner);
        if (service != null) {
            if (service.getPrivateCall() == null) return false;
            final State state = getPendingState(owner);
            if (state != null) {
                state.serviceOwner = getServiceOwner(service);
                handOff(context, state);
            }
            if (!PermissionRequest.hasPermission(Manifest.permission.RECORD_AUDIO)
                    || service.isCallingVideo() && !PermissionRequest.hasPermission(Manifest.permission.CAMERA)) {
                return requestPermissions(context, owner);
            }
            if (service.getCallState() != VoIPService.STATE_WAITING_INCOMING) return true;
            service.acceptIncomingCall();
            return true;
        }
        final State state = getPendingState(owner);
        if (state == null) return matchesOwner(handedOffState, owner) && handedOffState.intent.getBooleanExtra("accept", false);
        if (!hasPendingCall(state)) return false;
        if (!PermissionRequest.hasPermission(Manifest.permission.RECORD_AUDIO)
                || state.call.video && !PermissionRequest.hasPermission(Manifest.permission.CAMERA)) {
            return requestPermissions(context, owner);
        }
        return startPendingService(context, state, true);
    }

    public static void decline(Context context, int reason) {
        FileLog.d("VoIPPreNotification.decline("+reason+")");
        final State state = getPendingState(context);
        if (state == null) {
            FileLog.d("VoIPPreNotification.decline("+reason+"): pending intent or call is not found");
            return;
        }
        decline(context, state, reason);
    }

    public static boolean decline(Context context, int reason, Intent owner) {
        if (!isCurrentCallAction(owner)) return false;
        final VoIPService service = getActionService(owner);
        if (service != null) {
            if (service.getPrivateCall() == null) return false;
            service.declineIncomingCall(reason, null);
            return true;
        }
        final State state = getPendingState(owner);
        if (state == null) return false;
        decline(context, state, reason);
        return true;
    }

    private static void decline(Context context, State state, int reason) {
        final int account = state.currentAccount;
        final TL_phone.discardCall req = new TL_phone.discardCall();
        req.peer = new TLRPC.TL_inputPhoneCall();
        req.peer.access_hash = state.call.access_hash;
        req.peer.id = state.callId;
        req.duration = 0;
        req.connection_id = 0;
        switch (reason) {
            case VoIPService.DISCARD_REASON_DISCONNECT:
                req.reason = new TLRPC.TL_phoneCallDiscardReasonDisconnect();
                break;
            case VoIPService.DISCARD_REASON_MISSED:
                req.reason = new TLRPC.TL_phoneCallDiscardReasonMissed();
                break;
            case VoIPService.DISCARD_REASON_LINE_BUSY:
                req.reason = new TLRPC.TL_phoneCallDiscardReasonBusy();
                break;
            case VoIPService.DISCARD_REASON_HANGUP:
            default:
                req.reason = new TLRPC.TL_phoneCallDiscardReasonHangup();
                break;
        }
        FileLog.e("discardCall " + req.reason);
        dismiss(context, false);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("(VoIPPreNotification) error on phone.discardCall: " + error);
                }
            } else {
                if (response instanceof TLRPC.Updates && state.isAccountCurrent()) {
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("(VoIPPreNotification) phone.discardCall " + response);
                }
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public static void dismiss(Context context, boolean answered) {
        dismiss(context, answered, true);
    }

    public static boolean dismiss(Context context, boolean answered, Intent owner) {
        if (!isCurrentCallAction(owner) || getPendingState(owner) == null) return false;
        dismiss(context, answered);
        return true;
    }

    public static boolean dismiss(Context context, int account, long callId, boolean answered) {
        final State state = currentState;
        if (state == null || state.currentAccount != account || state.callId != callId
                || pendingVoIP != state.intent || pendingCall == null || pendingCall.id != callId) return false;
        dismiss(context, answered);
        return true;
    }

    private static void dismiss(Context context, boolean answered, boolean clearCall) {
        FileLog.d("VoIPPreNotification.dismiss()");
        final State state = currentState;
        final boolean owned = state != null && state.isPendingOwner();
        final long generation = ++pendingGeneration;
        pendingVoIP = null;
        pendingCall = null;
        if (!answered) handedOffState = null;
        if (clearCall && !answered && state != null) clearIncomingCall(state.launch);
        final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(VoIPService.ID_INCOMING_CALL_PRENOTIFICATION);
        stopRinging();
        if (state != null) state.destroy();
        if (!answered) {
            final VoIPService service = VoIPService.getSharedInstance();
            if (owned && state.isAccountCurrent() && generation == pendingGeneration
                    && currentState == state && pendingVoIP == null
                    && (service == null || service.getAccount() != state.currentAccount || service.getCallID() == state.callId)) {
                MessagesController.getInstance(state.currentAccount).ignoreSetOnline = false;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != pendingGeneration || pendingVoIP != null || currentState != state
                        || VoIPService.getSharedState() != state) return;
                final LaunchActivity activity = LaunchActivity.instance;
                if (activity != null && activity.voipLaunchedInBackground && VoIPService.getSharedInstance() == null) {
                    activity.voipLaunchedInBackground = false;
                    final VoIPFragment fragment = VoIPFragment.getInstance();
                    if (fragment != null) {
                        fragment.finish();
                    }
                    activity.moveTaskToBack(true);
                }
            });
        }
    }

}
