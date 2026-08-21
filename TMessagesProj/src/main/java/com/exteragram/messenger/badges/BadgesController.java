package com.exteragram.messenger.badges;

import android.content.Context;

import com.exteragram.messenger.api.dto.BadgeDTO;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.Map;

public final class BadgesController {

    public static final BadgesController INSTANCE = new BadgesController();

    public static BadgesController getInstance() {
        return INSTANCE;
    }

    public static final com.exteragram.messenger.badges.source.ApiBadgeSource apiBadgeSource =
            new com.exteragram.messenger.badges.source.ApiBadgeSource(
                    app.nimarkogram.messenger.badges.BadgesController.getInstance().apiBadgeSource);

    public static final BadgeDTO DEV_BADGE = BadgeDTO.fromReal(
            app.nimarkogram.messenger.badges.BadgesController.DEV_BADGE);
    public static final BadgeDTO SUPPORTER_BADGE = BadgeDTO.fromReal(
            app.nimarkogram.messenger.badges.BadgesController.SUPPORTER_BADGE);
    public static final BadgeDTO TRUSTED_BADGE = BadgeDTO.fromReal(
            app.nimarkogram.messenger.badges.BadgesController.TRUSTED_BADGE);

    private final app.nimarkogram.messenger.badges.BadgesController real;

    private BadgesController() {
        this.real = app.nimarkogram.messenger.badges.BadgesController.getInstance();
    }

    public void init(Context context) {
        real.init(context);
    }

    public void putPluginBadge(long userId, long emojiDocumentId, String text) {
        real.putPluginBadge(userId, emojiDocumentId, text);
    }


    public BadgeDTO i(TLObject obj) {
        return BadgeDTO.fromReal(real.i(obj));
    }

    public BadgeDTO getBadge(TLObject obj) {
        return i(obj);
    }

    public boolean r(TLObject obj) {
        return real.r(obj);
    }

    public boolean hasBadge(TLObject obj) {
        return r(obj);
    }

    public BadgeDTO o(TLRPC.User user) {
        return BadgeDTO.fromReal(real.o(user));
    }

    public BadgeDTO getSecondaryBadge(TLRPC.User user) {
        return o(user);
    }

    public BadgeDTO m(TLRPC.User user) {
        return BadgeDTO.fromReal(real.m(user));
    }

    public BadgeDTO getDefaultBadge(TLRPC.User user) {
        return m(user);
    }

    public BadgeDTO l() {
        return BadgeDTO.fromReal(real.l());
    }

    public BadgeDTO getDefaultBadge() {
        return l();
    }

    public BadgeDTO h() {
        return BadgeDTO.fromReal(real.h());
    }

    public BadgeDTO k() { return DEV_BADGE; }
    public BadgeDTO n() { return SUPPORTER_BADGE; }
    public BadgeDTO p() { return TRUSTED_BADGE; }

    public BadgeDTO getDEV_BADGE() { return DEV_BADGE; }
    public BadgeDTO getSUPPORTER_BADGE() { return SUPPORTER_BADGE; }
    public BadgeDTO getTRUSTED_BADGE() { return TRUSTED_BADGE; }

    public boolean e(TLRPC.User user) { return real.e(user); }
    public boolean d() { return real.d(); }
    public boolean canChangeBadge(TLRPC.User user) { return e(user); }
    public boolean canChangeBadge() { return d(); }
    public boolean u(TLRPC.User user) { return real.u(user); }
    public boolean w(long id) { return real.w(id); }
    public boolean x(TLRPC.Chat chat) { return real.x(chat); }
    public boolean y(long id) { return real.y(id); }

    public boolean z(TLRPC.User user, BadgeDTO badge) {
        return real.z(user, badge != null ? badge.toReal() : null);
    }

    public boolean q() { return real.q(); }
    public boolean t() { return real.t(); }


    public BadgeDTO getBadgeForUser(long userId) {
        return BadgeDTO.fromReal(real.getBadgeForUser(userId));
    }

    public boolean isBadgeAssigned(long userId) {
        return real.isBadgeAssigned(userId);
    }

    public Map<Long, BadgeDTO> getAllBadges() {
        Map<Long, app.nimarkogram.messenger.api.dto.BadgeDTO> src = real.getAllBadges();
        Map<Long, BadgeDTO> out = new HashMap<>(src.size());
        for (Map.Entry<Long, app.nimarkogram.messenger.api.dto.BadgeDTO> e : src.entrySet()) {
            out.put(e.getKey(), BadgeDTO.fromReal(e.getValue()));
        }
        return out;
    }

    public void setBadgeForUser(long userId, BadgeDTO badge) {
        real.setBadgeForUser(userId, badge != null ? badge.toReal() : null);
    }

    public void clearBadgeForUser(long userId) {
        real.clearBadgeForUser(userId);
    }

    public void refresh() {
        real.refresh();
    }
}
