/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class NimarkoExtra {

    private NimarkoExtra() {}

    public static final int APP_ID = org.telegram.messenger.BuildVars.APP_ID;
    public static final String APP_HASH = org.telegram.messenger.BuildVars.APP_HASH;

    public static final String SMS_HASH = "";

    public static final String[] FILE_NAME_HASH = new String[] { "" };
    public static final String[] GITLAB_RAW_URL_HASH = new String[] { "" };

    public static final String[] FILE_NAME_MARKETPLACE_HASH = new String[] { "" };
    public static final String[] GITLAB_RAW_URL_MARKETPLACE_HASH = new String[] { "" };

    public static final String[] FILE_NAME_BLOCKED_HASH = new String[] { "" };
    public static final String[] GITLAB_RAW_URL_BLOCKED_HASH = new String[] { "" };

    public static final String[] FILE_NAME_BADGE_COLORS_HASH = new String[] { "" };
    public static final String[] GITLAB_RAW_URL_BADGE_COLORS_HASH = new String[] { "" };

    public static final String[] FILE_NAME_TON_RATE_HASH = new String[] {
            "dG9u", "X3Vz", "ZHRf", "cmF0", "ZS5q", "c29u"
    };
    public static final String[] TON_RATE_URL_HASH = new String[] {
            "aHR0", "cHM6", "Ly9j", "ZG4u", "anNk", "ZWxp", "dnIu",
            "bmV0", "L25w", "bS9A", "ZmF3", "YXph", "aG1l", "ZDAv",
            "Y3Vy", "cmVu", "Y3kt", "YXBp", "QGxh", "dGVz", "dC92",
            "MS9j", "dXJy", "ZW5j", "aWVz", "L3Rv", "bi5q", "c29u"
    };
     
    public static final String ENDPOINT_FOR_DATE = "";
    public static final String ENDPOINT_FOR_DATE_SECRET = "";

    public static void getRegistrationDate(BaseFragment fragment, Activity parentActivity, long userID, long chatId) {

        if (chatId != 0L) {
            TLRPC.Chat chat = fragment.getMessagesController().getChat(chatId);
            if (chat != null) {
                CharSequence date;
                if (ChatObject.isInChat(chat)) {
                    date = AndroidUtilities.replaceTags(
                            LocaleController.formatString(
                                    R.string.CG_JoinedDate, chat.title,
                                    LocaleController.formatDateTime((long) chat.date, true)
                            )
                    );
                } else {
                    date = AndroidUtilities.replaceTags(
                            LocaleController.formatString(
                                    R.string.CG_CreatedDate, chat.title,
                                    LocaleController.formatDateTime((long) chat.date, true)
                            )
                    );
                }

                BulletinFactory.of(fragment.getLayoutContainer(), fragment.getResourceProvider())
                        .createSimpleBulletin(R.raw.chats_infotip, date)
                        .setDuration(Bulletin.DURATION_PROLONG)
                        .show();

                return;
            }
        }

        TLRPC.PeerSettings peerSettings = fragment.getMessagesController() != null
                ? fragment.getMessagesController().getPeerSettings(userID) : null;
        String regDateFromTelegram = peerSettings != null ? peerSettings.registration_month : null;

        if (regDateFromTelegram != null) {
            BulletinFactory.of(fragment.getLayoutContainer(), fragment.getResourceProvider())
                    
                    .createSimpleBulletin(R.raw.chats_infotip, regDateFromTelegram)
                    .setDuration(Bulletin.DURATION_PROLONG)
                    .show();
        }  

    }

    public static final class RegistrationInfo {
        public final long unixTimestamp;       
        public final String formattedDate;     
        public final String dayOfWeek;         
        public final String relativeYearsAgo;  
        public final String rawTelegramMonth;  

        public RegistrationInfo(long unixTimestamp, String formattedDate,
                                String dayOfWeek, String relativeYearsAgo,
                                String rawTelegramMonth) {
            this.unixTimestamp = unixTimestamp;
            this.formattedDate = formattedDate == null ? "" : formattedDate;
            this.dayOfWeek = dayOfWeek == null ? "" : dayOfWeek;
            this.relativeYearsAgo = relativeYearsAgo == null ? "" : relativeYearsAgo;
            this.rawTelegramMonth = rawTelegramMonth;
        }
    }

    public static RegistrationInfo resolveRegistrationInfo(BaseFragment fragment, long userID) {
        MessagesController mc = fragment != null ? fragment.getMessagesController() : null;
        TLRPC.PeerSettings peerSettings = mc != null ? mc.getPeerSettings(userID) : null;
        String regMonth = peerSettings != null ? peerSettings.registration_month : null;
        if (regMonth == null || regMonth.isEmpty()) {
            return new RegistrationInfo(0L, "", "", "", null);
        }

        long unixSeconds = 0L;
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM", Locale.getDefault());
            parser.setTimeZone(TimeZone.getDefault()); 
            Date d = parser.parse(regMonth);
            if (d != null) {
                unixSeconds = d.getTime() / 1000L;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        String formatted;
        String dayOfWeek = "";
        String yearsAgo = "";

        if (unixSeconds > 0L) {
            SimpleDateFormat monthYear = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
            monthYear.setTimeZone(TimeZone.getDefault());
            formatted = monthYear.format(new Date(unixSeconds * 1000L));

            Calendar cal = Calendar.getInstance(TimeZone.getDefault());
            cal.setTimeInMillis(unixSeconds * 1000L);
            dayOfWeek = localizedDayOfWeek(cal.get(Calendar.DAY_OF_WEEK));

            Calendar now = Calendar.getInstance(TimeZone.getDefault());
            int years = now.get(Calendar.YEAR) - cal.get(Calendar.YEAR);
            if (now.get(Calendar.MONTH) < cal.get(Calendar.MONTH)) {
                years--;
            }
            if (years > 0) {
                yearsAgo = LocaleController.formatPluralString("Years", years) + " "
                        + getString(R.string.NM_Extra_YearsAgo);
            }
        } else {
            formatted = regMonth;
        }

        return new RegistrationInfo(unixSeconds, formatted, dayOfWeek, yearsAgo, regMonth);
    }

    private static String localizedDayOfWeek(int calendarDay) {
        int resId;
        switch (calendarDay) {
            case Calendar.MONDAY:    resId = R.string.NM_Extra_DayOfWeek_Monday; break;
            case Calendar.TUESDAY:   resId = R.string.NM_Extra_DayOfWeek_Tuesday; break;
            case Calendar.WEDNESDAY: resId = R.string.NM_Extra_DayOfWeek_Wednesday; break;
            case Calendar.THURSDAY:  resId = R.string.NM_Extra_DayOfWeek_Thursday; break;
            case Calendar.FRIDAY:    resId = R.string.NM_Extra_DayOfWeek_Friday; break;
            case Calendar.SATURDAY:  resId = R.string.NM_Extra_DayOfWeek_Saturday; break;
            case Calendar.SUNDAY:    resId = R.string.NM_Extra_DayOfWeek_Sunday; break;
            default: return "";
        }
        return getString(resId);
    }

    public static void addBirthdayToCalendar(Activity parentActivity, long userId) {
        if (parentActivity == null) return;
        try {
            int account = UserConfig.selectedAccount;
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(userId);
            if (full == null || full.birthday == null) return;
            org.telegram.tgnet.tl.TL_account.TL_birthday b = full.birthday;
            TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            String title = user != null && user.first_name != null
                    ? "Birthday of " + user.first_name
                    : "Birthday";
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_EDIT);
            intent.setType("vnd.android.cursor.item/event");
            intent.putExtra("title", title);
            intent.putExtra("rrule", "FREQ=YEARLY;BYMONTH=" + b.month + ";BYMONTHDAY=" + b.day);
            Calendar cal = Calendar.getInstance();
            int year = b.year != 0 ? b.year : cal.get(Calendar.YEAR);
            cal.set(year, b.month - 1, b.day, 9, 0);
            intent.putExtra("beginTime", cal.getTimeInMillis());
            cal.set(year, b.month - 1, b.day, 10, 0);
            intent.putExtra("endTime", cal.getTimeInMillis());
            intent.putExtra("allDay", false);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            parentActivity.startActivity(intent);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static StringBuilder getProfileDC(TLRPC.User user, TLRPC.Chat chat) {
        return getProfileDC(UserConfig.selectedAccount, user, chat);
    }

    public static StringBuilder getProfileDC(int account, TLRPC.User user, TLRPC.Chat chat) {
        StringBuilder sb = new StringBuilder();
        int dcId = resolveDcId(account, user, chat);

        if (dcId > 0) {
            sb.append("DC: ")
                    .append(dcId)
                    .append(", ")
                    .append(ResourcesUtils.getDCName(dcId))
                    .append(", ")
                    .append(ResourcesUtils.getDCGeo(dcId));
        } else {
            sb.append("DC: ").append(getString(R.string.NumberUnknown));
        }

        return sb;
    }

    public static int resolveDcId(TLRPC.User user, TLRPC.Chat chat) {
        return resolveDcId(UserConfig.selectedAccount, user, chat);
    }

    public static int resolveDcId(int account, TLRPC.User user, TLRPC.Chat chat) {
        
        if (chat != null && chat.photo != null && chat.photo.dc_id > 0) {
            return chat.photo.dc_id;
        }
        if (user == null) {
            return 0;
        }

        boolean isSelf = org.telegram.messenger.UserObject.isUserSelf(user)
                || user.id == UserConfig.getInstance(account).getClientUserId();
        if (isSelf) {
            int myDc = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentDatacenterId();
            
            if (myDc > 0 && myDc != Integer.MAX_VALUE) {
                return myDc;
            }
            // Self DC still unresolved via the live connection: do NOT return here — fall through to the
            
        }

        int direct = readDirectUserDcId(user);
        if (direct > 0) {
            return direct;
        }

        if (user.photo != null && user.photo.dc_id > 0) {
            return user.photo.dc_id;
        }

        TLRPC.UserFull userFull = MessagesController.getInstance(account).getUserFull(user.id);
        if (userFull != null) {
            if (userFull.profile_photo != null && userFull.profile_photo.dc_id > 0) {
                return userFull.profile_photo.dc_id;
            } else if (userFull.personal_photo != null && userFull.personal_photo.dc_id > 0) {
                return userFull.personal_photo.dc_id;
            } else if (userFull.fallback_photo != null && userFull.fallback_photo.dc_id > 0) {
                return userFull.fallback_photo.dc_id;
            }
        }
        return 0;
    }

    private static volatile Field cachedUserDcIdField;
    private static volatile boolean userDcIdFieldResolved;

    private static int readDirectUserDcId(TLRPC.User user) {
        try {
            if (!userDcIdFieldResolved) {
                synchronized (NimarkoExtra.class) {
                    if (!userDcIdFieldResolved) {
                        Field f = null;
                        try {
                            f = TLRPC.User.class.getField("dc_id");
                        } catch (NoSuchFieldException ignored) {
                            
                        }
                        cachedUserDcIdField = f;
                        userDcIdFieldResolved = true;
                    }
                }
            }
            Field f = cachedUserDcIdField;
            if (f != null) {
                Object v = f.get(user);
                if (v instanceof Integer) {
                    int i = (Integer) v;
                    return i > 0 ? i : 0;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

}
