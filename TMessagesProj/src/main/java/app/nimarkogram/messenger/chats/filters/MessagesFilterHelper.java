/**
 * This is the source code of Cherrygram for Android, ported to NimarkoGram.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.chats.filters;

import android.text.TextUtils;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import app.nimarkogram.messenger.NimarkoConfig;

public class MessagesFilterHelper {

    public static final MessagesFilterHelper INSTANCE = new MessagesFilterHelper();

    private static final String EXCLUDED_LIST_KEY = "msgFiltersExcludedChats";

    private static final char[] OBFUSCATE_GLYPHS = {
            (char) 10252, (char) 10338, (char) 10385,
            (char) 10280, (char) 10277, (char) 10286, (char) 10321
    };

    private static final char[] TRANSLIT_KEYS;
    private static final char[] TRANSLIT_VALUES;
    static {
        
        char[][] pairs = {
                {'a', 1072}, {'@', 1072}, {'b', 1073}, {'c', 1089}, {231,  1089},
                {'d', 1076}, {'e', 1077}, {1105, 1077}, {233,  1077},
                {'f', 1092}, {'g', 1075}, {'h', 1093}, {'i', 1080}, {'1', 1080}, {'!', 1080},
                {'j', 1078}, {'k', 1082}, {'l', 1083}, {'m', 1084}, {'n', 1085},
                {'o', 1086}, {'0', 1086}, {'p', 1088}, {'q', 1082}, {'r', 1088},
                {'s', 1089}, {'$', 1089}, {'t', 1090}, {'7', 1090},
                {'u', 1091}, {'v', 1074}, {'w', 1074}, {'x', 1093}, {'y', 1091},
                {'z', 1079}, {'3', 1079},
                
                {1072,1072},{1073,1073},{1074,1074},{1075,1075},{1076,1076},
                {1077,1077},{1078,1078},{1079,1079},{1080,1080},{1081,1081},
                {1082,1082},{1083,1083},{1084,1084},{1085,1085},{1086,1086},
                {1087,1087},{1088,1088},{1089,1089},{1090,1090},{1091,1091},
                {1092,1092},{1093,1093},{1094,1094},{1095,1095},{1096,1096},
                {1097,1097},{1098,1098},{1099,1099},{1100,1100},{1101,1101},
                {1102,1102},{1103,1103}
        };
        TRANSLIT_KEYS = new char[pairs.length];
        TRANSLIT_VALUES = new char[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            TRANSLIT_KEYS[i] = pairs[i][0];
            TRANSLIT_VALUES[i] = pairs[i][1];
        }
    }

    private static int translitLookup(int c) {
        for (int i = 0; i < TRANSLIT_KEYS.length; i++) {
            if (TRANSLIT_KEYS[i] == c) return TRANSLIT_VALUES[i];
        }
        return c;
    }

    private static final class FoldedText {
        final String text;
        final int[] originalUtf16Offsets;

        FoldedText(String text, int[] originalUtf16Offsets) {
            this.text = text;
            this.originalUtf16Offsets = originalUtf16Offsets;
        }

        int originalOffset(int foldedUtf16Offset) {
            int index = Math.max(0, Math.min(foldedUtf16Offset, originalUtf16Offsets.length - 1));
            return originalUtf16Offsets[index];
        }
    }

    private static FoldedText foldForSearch(String source, boolean translit) {
        if (source == null || source.isEmpty()) return new FoldedText(source == null ? "" : source, new int[]{0});
        StringBuilder out = new StringBuilder(source.length());
        int[] offsets = new int[Math.max(4, source.length() * 2 + 1)];
        offsets[0] = 0;
        for (int originalStart = 0; originalStart < source.length();) {
            int codePoint = Character.codePointAt(source, originalStart);
            int originalEnd = originalStart + Character.charCount(codePoint);
            int foldedCodePoint = Character.toLowerCase(codePoint);
            if (translit) foldedCodePoint = translitLookup(foldedCodePoint);
            int foldedStart = out.length();
            out.appendCodePoint(foldedCodePoint);
            int foldedEnd = out.length();
            if (foldedEnd >= offsets.length) offsets = Arrays.copyOf(offsets, offsets.length * 2);
            offsets[foldedStart] = originalStart;
            for (int i = foldedStart + 1; i < foldedEnd; i++) offsets[i] = originalStart;
            offsets[foldedEnd] = originalEnd;
            originalStart = originalEnd;
        }
        return new FoldedText(out.toString(), Arrays.copyOf(offsets, out.length() + 1));
    }

    private static boolean isCodePointBoundary(String value, int offset) {
        return offset >= 0 && offset <= value.length()
                && !(offset > 0 && offset < value.length()
                && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset)));
    }

    private static int indexOfCodePointSafe(String haystack, String needle, int from) {
        int searchFrom = Math.max(0, from);
        while (searchFrom <= haystack.length()) {
            int index = haystack.indexOf(needle, searchFrom);
            if (index < 0) return -1;
            int end = index + needle.length();
            if (isCodePointBoundary(haystack, index) && isCodePointBoundary(haystack, end)) return index;
            searchFrom = index + 1;
        }
        return -1;
    }

    public static String translitFold(String s) {
        if (s == null || s.isEmpty()) return s;
        return foldForSearch(s, true).text;
    }

    public CharSequence obfuscatePreview(CharSequence text) {
        if (text == null || text.length() == 0) return text;
        List<String> keywords = getKeywords();
        if (keywords.isEmpty()) return text;
        String original = text.toString();
        boolean translit = NimarkoConfig.isMsgFiltersDetectTranslit();
        FoldedText folded = foldForSearch(original, translit);
        StringBuilder sb = new StringBuilder(text.toString());
        for (String kw : keywords) {
            if (kw == null || kw.isEmpty()) continue;
            int from = 0;
            while (true) {
                int idx = indexOfCodePointSafe(folded.text, kw, from);
                if (idx < 0) break;
                int originalStart = folded.originalOffset(idx);
                int originalEnd = folded.originalOffset(idx + kw.length());
                int[] expanded = expandWordBoundary(original, originalStart, originalEnd);
                for (int p = expanded[0]; p < expanded[1] && p < sb.length(); p++) {
                    sb.setCharAt(p, OBFUSCATE_GLYPHS[p % OBFUSCATE_GLYPHS.length]);
                }
                from = idx + kw.length();
            }
        }
        return sb.toString();
    }

    public static int[] expandWordBoundary(CharSequence text, int start, int end) {
        if (text == null) return new int[] {start, end};
        int len = text.length();
        int s = Math.max(0, Math.min(start, len));
        int e = Math.max(s, Math.min(end, len));
        while (s > 0) {
            int codePoint = Character.codePointBefore(text, s);
            if (!Character.isLetterOrDigit(codePoint)) break;
            s -= Character.charCount(codePoint);
        }
        while (e < len) {
            int codePoint = Character.codePointAt(text, e);
            if (!Character.isLetterOrDigit(codePoint)) break;
            e += Character.charCount(codePoint);
        }
        return new int[] {s, e};
    }

    private static boolean isWordBoundary(CharSequence text, int start, int len) {
        if (text == null) return false;
        int after = start + len;
        boolean beforeIsWord = start > 0 && Character.isLetterOrDigit(Character.codePointBefore(text, start));
        boolean afterIsWord = after < text.length() && Character.isLetterOrDigit(Character.codePointAt(text, after));
        return !beforeIsWord && !afterIsWord;
    }

    private final Object lock = new Object();
    private List<String> cachedKeywords = Collections.emptyList();
    private String cachedKeywordSource;
    private boolean cachedExactWord = false;
    private boolean cachedTranslit = false;

    private List<Pattern> cachedRegex = Collections.emptyList();
    private String cachedRegexSource;
    private boolean cachedRegexEnabled = false;

    private Set<Long> cachedChatWhitelist = Collections.emptySet();
    private Set<Long> cachedChatBlacklist = Collections.emptySet();
    private String cachedChatWhitelistSource;
    private String cachedChatBlacklistSource;
    private String cachedChatWhitelistIdentity;
    private String cachedChatBlacklistIdentity;
    private volatile List<Object> cachedConfigurationToken;

    private static final int MAX_REVEALED_MESSAGES = 4096;
    private final LinkedHashMap<String, Boolean> revealedMessages = new LinkedHashMap<String, Boolean>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
            return size() > MAX_REVEALED_MESSAGES;
        }
    };

    private MessagesFilterHelper() {
    }

    public String getExcludedList() {
        return EXCLUDED_LIST_KEY;
    }

    public ArrayList<String> getArrayList(String key) {
        return getArrayList(UserConfig.selectedAccount, key);
    }

    public ArrayList<String> getArrayList(int account, String key) {
        String raw = EXCLUDED_LIST_KEY.equals(key)
                ? NimarkoConfig.getMsgFiltersExcludedChats(account)
                : NimarkoConfig.prefs().getString(key, "");
        ArrayList<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public void saveArrayList(ArrayList<String> values, String key) {
        saveArrayList(UserConfig.selectedAccount, values, key);
    }

    public void saveArrayList(int account, ArrayList<String> values, String key) {
        long ownerUid = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                ? UserConfig.getInstance(account).getClientUserId() : 0L;
        saveArrayList(account, ownerUid, values, key);
    }

    public boolean saveArrayList(int account, long ownerUid, ArrayList<String> values, String key) {
        StringBuilder sb = new StringBuilder();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(values.get(i));
            }
        }
        if (EXCLUDED_LIST_KEY.equals(key)) {
            return NimarkoConfig.setMsgFiltersExcludedChats(account, ownerUid, sb.toString());
        } else {
            NimarkoConfig.editor().putString(key, sb.toString()).apply();
            return true;
        }
    }

    public int getExcludedChatsCount() {
        return getExcludedChatsCount(UserConfig.selectedAccount);
    }

    public int getExcludedChatsCount(int account) {
        return getArrayList(account, EXCLUDED_LIST_KEY).size();
    }

    private List<String> getKeywords() {
        String raw = NimarkoConfig.getMsgFiltersElements();
        if (raw == null) raw = "";
        boolean exact = NimarkoConfig.isMsgFiltersMatchExactWord();
        boolean translit = NimarkoConfig.isMsgFiltersDetectTranslit();
        synchronized (lock) {
            if (raw.equals(cachedKeywordSource) && exact == cachedExactWord && translit == cachedTranslit) {
                return cachedKeywords;
            }
            List<String> compiled = new ArrayList<>();
            if (!raw.isEmpty()) {
                
                ArrayList<String> tokens = new ArrayList<>();
                try {
                    Matcher pm = Pattern.compile("\\(([^)]+)\\)").matcher(raw);
                    while (pm.find()) {
                        String inner = pm.group(1);
                        if (inner != null) {
                            String t = inner.trim();
                            if (!t.isEmpty()) tokens.add(t);
                        }
                    }
                } catch (PatternSyntaxException ignored) {
                    
                }
                String remainder = raw.replaceAll("\\([^)]+\\)", "");
                for (String token : remainder.split("[,;\\s]+")) {
                    String t = token.trim();
                    if (!t.isEmpty()) tokens.add(t);
                }
                for (String trimmed : tokens) {
                    String folded = foldForSearch(trimmed, translit).text;
                    if (!folded.isEmpty() && !compiled.contains(folded)) compiled.add(folded);
                }
            }
            cachedKeywords = compiled;
            cachedKeywordSource = raw;
            cachedExactWord = exact;
            cachedTranslit = translit;
            return compiled;
        }
    }

    private boolean isInExcluded(long dialogId, int account) {
        ArrayList<String> excluded = getArrayList(account, EXCLUDED_LIST_KEY);
        if (excluded.isEmpty()) return false;
        
        String s = String.valueOf(dialogId);
        if (excluded.contains(s)) return true;
        if (dialogId > 0 && excluded.contains("-" + s)) return true;
        if (dialogId < 0 && excluded.contains(String.valueOf(-dialogId))) return true;
        return false;
    }

    private List<Pattern> getRegexPatterns() {
        boolean enabled = NimarkoConfig.isMsgFiltersUseRegex();
        String raw = NimarkoConfig.getMsgFiltersRegexPatterns();
        if (raw == null) raw = "";
        synchronized (lock) {
            if (enabled == cachedRegexEnabled && raw.equals(cachedRegexSource)) {
                return cachedRegex;
            }
            List<Pattern> compiled = new ArrayList<>();
            if (enabled && !raw.isEmpty()) {
                
                String[] tokens = raw.split("\\n");
                for (String token : tokens) {
                    String trimmed = token.trim();
                    if (trimmed.isEmpty() || !isSafeRegex(trimmed)) continue;
                    try {
                        compiled.add(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                    } catch (PatternSyntaxException ignored) {
                        
                    }
                }
            }
            cachedRegex = compiled;
            cachedRegexSource = raw;
            cachedRegexEnabled = enabled;
            return compiled;
        }
    }

    private HashSet<Long> parseChatIdSet(String raw) {
        HashSet<Long> out = new HashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try { out.add(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static String filterIdentity(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return "";
        return account + ":" + UserConfig.getInstance(account).getClientUserId();
    }

    private Set<Long> getChatWhitelist(int account) {
        String raw = NimarkoConfig.getMsgFiltersChatWhitelist(account);
        if (raw == null) raw = "";
        String identity = filterIdentity(account);
        synchronized (lock) {
            if (!raw.equals(cachedChatWhitelistSource)
                    || !identity.equals(cachedChatWhitelistIdentity)) {
                cachedChatWhitelist = Collections.unmodifiableSet(parseChatIdSet(raw));
                cachedChatWhitelistSource = raw;
                cachedChatWhitelistIdentity = identity;
            }
            return cachedChatWhitelist;
        }
    }

    private Set<Long> getChatBlacklist(int account) {
        String raw = NimarkoConfig.getMsgFiltersChatBlacklist(account);
        if (raw == null) raw = "";
        String identity = filterIdentity(account);
        synchronized (lock) {
            if (!raw.equals(cachedChatBlacklistSource)
                    || !identity.equals(cachedChatBlacklistIdentity)) {
                cachedChatBlacklist = Collections.unmodifiableSet(parseChatIdSet(raw));
                cachedChatBlacklistSource = raw;
                cachedChatBlacklistIdentity = identity;
            }
            return cachedChatBlacklist;
        }
    }

    public boolean isChatFiltered(long chatId) {
        return isChatFiltered(chatId, UserConfig.selectedAccount);
    }

    public boolean isChatFiltered(long chatId, int account) {
        
        try {
            if (chatId == UserConfig.getInstance(account).getClientUserId()) return false;
        } catch (Throwable ignored) {}
        Set<Long> blacklist = getChatBlacklist(account);
        if (!blacklist.isEmpty() && blacklist.contains(chatId)) return false;
        if (isInExcluded(chatId, account)) return false;
        Set<Long> whitelist = getChatWhitelist(account);
        if (!whitelist.isEmpty() && !whitelist.contains(chatId)) return false;
        return true;
    }

    private boolean matchesAnyRegex(CharSequence haystack) {
        if (haystack == null || haystack.length() == 0) return false;
        List<Pattern> regex = getRegexPatterns();
        if (regex.isEmpty()) return false;
        String hay = haystack.length() > 8192 ? haystack.subSequence(0, 8192).toString() : haystack.toString();
        for (Pattern p : regex) {
            try {
                if (p.matcher(hay).find()) return true;
            } catch (Throwable ignored) {
                
            }
        }
        return false;
    }

    public boolean matchesAnyRule(CharSequence text, long chatId) {
        return matchesAnyRule(text, chatId, UserConfig.selectedAccount);
    }

    public boolean matchesAnyRule(CharSequence text, long chatId, int account) {
        if (!NimarkoConfig.isEnableMsgFilters()) return false;
        if (!isChatFiltered(chatId, account)) return false;
        if (text == null || text.length() == 0) return false;

        boolean useRegex = NimarkoConfig.isMsgFiltersUseRegex();
        boolean hasKeywords = !getKeywords().isEmpty();
        boolean hasRegex = useRegex && !getRegexPatterns().isEmpty();

        int logic = NimarkoConfig.getMsgFiltersLogic();
        if (logic == NimarkoConfig.MSG_FILTERS_LOGIC_AND && hasKeywords && hasRegex) {
            
            return matchesAnyKeyword(text) && matchesAnyRegex(text);
        }
        
        if (hasKeywords && matchesAnyKeyword(text)) return true;
        if (hasRegex && matchesAnyRegex(text)) return true;
        return false;
    }

    private boolean matchesAnyKeyword(CharSequence haystack) {
        if (haystack == null || haystack.length() == 0) return false;
        List<String> keywords = getKeywords();
        if (keywords.isEmpty()) return false;
        String original = haystack.toString();
        boolean exact = NimarkoConfig.isMsgFiltersMatchExactWord();
        boolean translit = NimarkoConfig.isMsgFiltersDetectTranslit();
        
        FoldedText hayFolded = foldForSearch(original, translit);
        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            int idx = indexOfCodePointSafe(hayFolded.text, kw, 0);
            while (idx >= 0) {
                int originalStart = hayFolded.originalOffset(idx);
                int originalEnd = hayFolded.originalOffset(idx + kw.length());
                if (!exact || isWordBoundary(original, originalStart, originalEnd - originalStart)) {
                    return true;
                }
                idx = indexOfCodePointSafe(hayFolded.text, kw, idx + 1);
            }
        }
        return false;
    }

    public boolean shouldBlockMessage(MessageObject msg) {
        if (msg == null) return false;
        if (!NimarkoConfig.isEnableMsgFilters()) return false;

        long dialogId;
        try {
            dialogId = msg.getDialogId();
        } catch (Throwable t) {
            dialogId = 0L;
        }
        
        if (!isChatFiltered(dialogId, msg.currentAccount)) return false;

        if (NimarkoConfig.isMsgFiltersHideFromBlocked() && msg.messageOwner != null && msg.messageOwner.from_id != null) {
            try {
                long fromId = senderPeerId(msg);
                MessagesController controller = MessagesController.getInstance(msg.currentAccount);
                if (controller != null && controller.blockePeers != null && controller.blockePeers.indexOfKey(fromId) >= 0) {
                    return true;
                }
            } catch (Throwable ignored) {
                
            }
        }

        CharSequence text = msg.messageText;
        if (text == null && msg.messageOwner != null) {
            text = msg.messageOwner.message;
        }
        if (matchesAnyRule(text, dialogId, msg.currentAccount)) {
            return true;
        }
        if (msg.caption != null && matchesAnyRule(msg.caption, dialogId, msg.currentAccount)) {
            return true;
        }
        return false;
    }

    private static long senderPeerId(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.messageOwner.from_id == null) return 0L;
        return msg.messageOwner.from_id.user_id != 0 ? msg.messageOwner.from_id.user_id
                : (msg.messageOwner.from_id.chat_id != 0 ? -msg.messageOwner.from_id.chat_id
                : -msg.messageOwner.from_id.channel_id);
    }

    private static boolean isSenderBlockedNow(MessageObject msg) {
        long fromId = senderPeerId(msg);
        if (fromId == 0L || msg == null) return false;
        try {
            MessagesController controller = MessagesController.getInstance(msg.currentAccount);
            return controller != null && controller.blockePeers != null
                    && controller.blockePeers.indexOfKey(fromId) >= 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public boolean shouldCollapseMessage(MessageObject msg) {
        if (msg == null || !NimarkoConfig.isMsgFiltersCollapseAutomatically()) return false;
        return shouldCollapseMessage(msg, shouldBlockMessage(msg));
    }

    public boolean shouldCollapseMessage(MessageObject msg, boolean blocked) {
        if (msg == null || !NimarkoConfig.isMsgFiltersCollapseAutomatically() || !blocked) return false;
        String key = messageKey(msg);
        if (key == null) return false;
        synchronized (revealedMessages) {
            return !revealedMessages.containsKey(key);
        }
    }

    public void revealMessage(MessageObject msg) {
        String key = messageKey(msg);
        if (key == null) return;
        synchronized (revealedMessages) {
            revealedMessages.put(key, Boolean.TRUE);
        }
    }

    public void clearRevealedMessages() {
        synchronized (revealedMessages) {
            revealedMessages.clear();
        }
    }

    private static String messageKey(MessageObject msg) {
        if (msg == null || msg.messageOwner == null
                || msg.getId() == 0 && msg.messageOwner.random_id == 0) return null;
        String identity = filterIdentity(msg.currentAccount);
        if (identity.isEmpty()) return null;
        long randomId = msg.messageOwner.random_id;
        return identity + ":" + msg.getDialogId() + ":"
                + (randomId != 0 ? "r" + randomId : "m" + msg.getId());
    }

    public ArrayList<TLRPC.MessageEntity> addSpoilerEntities(MessageObject msg,
                                                             ArrayList<TLRPC.MessageEntity> orig) {
        CharSequence target = msg != null ? msg.messageText : null;
        if (target == null && msg != null && msg.messageOwner != null) target = msg.messageOwner.message;
        return addSpoilerEntities(msg, target, orig);
    }

    public ArrayList<TLRPC.MessageEntity> addSpoilerEntities(MessageObject msg,
                                                             CharSequence target,
                                                             ArrayList<TLRPC.MessageEntity> orig) {
        ArrayList<TLRPC.MessageEntity> out = orig != null ? new ArrayList<>(orig) : new ArrayList<>();
        if (!NimarkoConfig.isEnableMsgFilters()) return out;
        if (msg == null) return out;
        long dialogId;
        try { dialogId = msg.getDialogId(); } catch (Throwable t) { dialogId = 0L; }
        
        if (!isChatFiltered(dialogId, msg.currentAccount)) return out;
        if (NimarkoConfig.isMsgFiltersHideAll() && shouldBlockMessage(msg)) {
            if (target != null && target.length() > 0) {
                boolean covered = false;
                for (int i = 0; i < out.size(); i++) {
                    TLRPC.MessageEntity entity = out.get(i);
                    if (entity instanceof TLRPC.TL_messageEntitySpoiler
                            && entity.offset <= 0 && entity.offset + entity.length >= target.length()) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                    spoiler.offset = 0;
                    spoiler.length = target.length();
                    out.add(spoiler);
                }
            }
            return out;
        }
        addSpoilerRunsFor(target, out);
        return out;
    }

    public ArrayList<TLRPC.MessageEntity> addSpoilerEntities(MessageObject msg) {
        ArrayList<TLRPC.MessageEntity> base = (msg != null && msg.messageOwner != null) ? msg.messageOwner.entities : null;
        return addSpoilerEntities(msg, base);
    }

    public CharSequence addSpoilerEntities(CharSequence text) {
        if (text == null || text.length() == 0) return text;
        if (!NimarkoConfig.isEnableMsgFilters()) return text;
        return obfuscatePreview(text);
    }

    private void addSpoilerRunsFor(CharSequence text, ArrayList<TLRPC.MessageEntity> out) {
        if (text == null || text.length() == 0) return;
        List<String> keywords = getKeywords();
        if (keywords.isEmpty()) return;
        String original = text.toString();
        boolean exact = NimarkoConfig.isMsgFiltersMatchExactWord();
        boolean translit = NimarkoConfig.isMsgFiltersDetectTranslit();
        boolean detectEntities = NimarkoConfig.isMsgFiltersDetectEntities();
        
        FoldedText haySearch = foldForSearch(original, translit);
        HashSet<Long> seenSpans = new HashSet<>();
        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            int from = 0;
            while (true) {
                int idx = indexOfCodePointSafe(haySearch.text, kw, from);
                if (idx < 0) break;
                int kwLen = kw.length();
                int start, len;
                int originalStart = haySearch.originalOffset(idx);
                int originalEnd = haySearch.originalOffset(idx + kwLen);
                if (exact) {
                    
                    if (!isWordBoundary(original, originalStart, originalEnd - originalStart)) {
                        from = idx + 1;
                        continue;
                    }
                    start = originalStart;
                    len = originalEnd - originalStart;
                } else {
                    
                    int[] exp = expandWordBoundary(original, originalStart, originalEnd);
                    start = exp[0];
                    len = exp[1] - exp[0];
                }
                if (len > 0) {
                    long span = (((long) start) << 32) | (long) len;
                    if (seenSpans.add(span)) {
                        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                        spoiler.offset = start;
                        spoiler.length = len;
                        out.add(spoiler);
                    }
                }
                from = idx + kwLen;
            }
        }
        
        if (NimarkoConfig.isMsgFiltersUseRegex()) {
            String hayOriginal = text.toString();
            for (Pattern p : getRegexPatterns()) {
                try {
                    Matcher m = p.matcher(hayOriginal);
                    while (m.find()) {
                        int start = m.start();
                        int len = m.end() - start;
                        if (len <= 0) continue;
                        long span = (((long) start) << 32) | (long) len;
                        if (!seenSpans.add(span)) continue;
                        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                        spoiler.offset = start;
                        spoiler.length = len;
                        out.add(spoiler);
                    }
                } catch (Throwable ignored) {
                    
                }
            }
        }
        
        if (detectEntities) {
            
            int size = out.size();
            for (int i = 0; i < size; i++) {
                TLRPC.MessageEntity e = out.get(i);
                if (e == null) continue;
                if (e instanceof TLRPC.TL_messageEntityUrl
                        || e instanceof TLRPC.TL_messageEntityTextUrl
                        || e instanceof TLRPC.TL_messageEntityMention
                        || e instanceof TLRPC.TL_messageEntityMentionName
                        || e instanceof TLRPC.TL_messageEntityHashtag
                        || e instanceof TLRPC.TL_messageEntityEmail) {
                    long span = (((long) e.offset) << 32) | (long) e.length;
                    if (!seenSpans.add(span)) continue;
                    TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                    spoiler.offset = e.offset;
                    spoiler.length = e.length;
                    out.add(spoiler);
                }
            }
        }
    }

    public Object configurationToken() {
        return configurationToken(UserConfig.selectedAccount);
    }

    private Object configurationToken(int account) {
        List<Object> next = Arrays.asList(NimarkoConfig.isEnableMsgFilters(), NimarkoConfig.getMsgFiltersElements(),
                NimarkoConfig.isMsgFiltersDetectTranslit(), NimarkoConfig.isMsgFiltersMatchExactWord(),
                NimarkoConfig.isMsgFiltersDetectEntities(), NimarkoConfig.isMsgFiltersHideFromBlocked(),
                NimarkoConfig.isMsgFiltersHideAll(), filterIdentity(account),
                NimarkoConfig.getMsgFiltersExcludedChats(account),
                NimarkoConfig.isMsgFiltersUseRegex(), NimarkoConfig.getMsgFiltersRegexPatterns(),
                NimarkoConfig.getMsgFiltersChatWhitelist(account), NimarkoConfig.getMsgFiltersChatBlacklist(account),
                NimarkoConfig.getMsgFiltersLogic());
        List<Object> current = cachedConfigurationToken;
        if (next.equals(current)) return current;
        synchronized (lock) {
            current = cachedConfigurationToken;
            if (next.equals(current)) return current;
            cachedConfigurationToken = next;
            return next;
        }
    }

    private static List<Object> entityRevision(List<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();
        ArrayList<Object> revision = new ArrayList<>(entities.size());
        for (TLRPC.MessageEntity entity : entities) {
            if (entity == null) {
                revision.add(null);
                continue;
            }
            revision.add(Arrays.asList(entity.getClass().getName(), entity.flags, entity.collapsed,
                    entity.offset, entity.length, entity.url, entity.language));
        }
        return revision;
    }

    private static String textSnapshot(CharSequence text) {
        return text == null ? null : text.toString();
    }

    public Object messageVerdictToken(MessageObject message) {
        boolean blocked = NimarkoConfig.isMsgFiltersHideFromBlocked() && isSenderBlockedNow(message);
        if (message == null) {
            return Arrays.asList(configurationToken(), -1, 0L, blocked, null, null, null,
                    Collections.emptyList());
        }
        TLRPC.Message owner = message.messageOwner;
        return Arrays.asList(configurationToken(message.currentAccount), message.currentAccount,
                senderPeerId(message), blocked,
                textSnapshot(message.messageText), owner == null ? null : owner.message,
                textSnapshot(message.caption), entityRevision(owner == null ? null : owner.entities));
    }

    private static boolean isSafeRegex(String regex) {
        if (regex.length() > 256) return false;
        if (regex.contains("(?") || regex.matches(".*\\\\[1-9].*")) return false;
        int groupDepth = 0;
        boolean groupHasQuantifier = false;
        boolean escaped = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '(') { groupDepth++; groupHasQuantifier = false; }
            else if (c == ')') {
                if (i + 1 < regex.length() && (regex.charAt(i + 1) == '+' || regex.charAt(i + 1) == '*'
                        || regex.charAt(i + 1) == '{') && groupHasQuantifier) return false;
                groupDepth = Math.max(0, groupDepth - 1);
            } else if (groupDepth > 0 && (c == '+' || c == '*' || c == '{')) {
                groupHasQuantifier = true;
            }
        }
        return true;
    }
}
