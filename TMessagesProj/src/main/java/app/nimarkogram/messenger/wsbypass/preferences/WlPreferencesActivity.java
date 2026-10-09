/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.wsbypass.preferences;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import app.nimarkogram.messenger.preferences.BasePreferencesActivity;
import app.nimarkogram.messenger.wsbypass.WlAccess;

public final class WlPreferencesActivity extends BasePreferencesActivity {
    private static final int ENABLE = 301, STATUS = 302, PAY = 303, EXPIRY = 304,
            PRICE = 305;
    private static final int MAX_PAYMENT_POLLS = 24;
    private int account = UserConfig.selectedAccount;
    private long owner = UserConfig.getInstance(account).getClientUserId();
    private long ownerEpoch = WlAccess.accountEpoch(account);
    private int orderAccount;
    private long orderOwner, orderEpoch, startingExpiry;
    private WlAccess.Checkout order;
    private boolean visible, busy, waitingPayment, paymentConfirmed, activateAfterPayment;
    private int generation, paymentGeneration, paymentPolls, paymentErrors;
    private String openedUrl = "";
    private String status = "loading";
    private final Runnable accessChanged = this::onAccessChanged;
    private final Runnable paymentPoll = () -> {
        if (visible && !busy && waitingPayment) {
            if (paymentPolls < MAX_PAYMENT_POLLS) paymentPolls++;
            if (order != null && orderCurrent() && !paymentConfirmed) pollOrder();
            else refresh();
        }
    };

    @Override public String getTitle() { return text(R.string.NM_WL_Title); }

    @Override public void onResume() {
        syncAccount();
        prepareRefreshStatus();
        super.onResume();
        visible = true;
        WlAccess.addListener(accessChanged);
        paymentPolls = 0;
        paymentErrors = 0;
        if (!busy) refresh();
    }
    private boolean syncAccount() {
        int selected = UserConfig.selectedAccount;
        long selectedUid = UserConfig.getInstance(selected).getClientUserId();
        long selectedEpoch = WlAccess.accountEpoch(selected);
        if (account == selected && owner == selectedUid && ownerEpoch == selectedEpoch) return false;
        account = selected;
        owner = selectedUid;
        ownerEpoch = selectedEpoch;
        generation++;
        busy = false;
        clearPayment();
        status = "loading";
        return true;
    }
    private boolean screenCurrent(int expected, long expectedUid) {
        return visible && generation == expected && owner == expectedUid
                && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).isClientActivated()
                && WlAccess.accountEpoch(account) == ownerEpoch
                && UserConfig.getInstance(account).getClientUserId() == expectedUid;
    }

    private boolean orderCurrent() {
        return orderOwner > 0 && UserConfig.getInstance(orderAccount).isClientActivated()
                && WlAccess.accountEpoch(orderAccount) == orderEpoch
                && UserConfig.getInstance(orderAccount).getClientUserId() == orderOwner;
    }
    private void onAccessChanged() {
        if (!visible) return;
        if (syncAccount() && !busy) refresh();
        reload();
    }

    @Override public void onPause() {
        visible = false;
        generation++;
        busy = false;
        WlAccess.removeListener(accessChanged);
        AndroidUtilities.cancelRunOnUIThread(paymentPoll);
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        visible = false;
        generation++;
        busy = false;
        clearPayment();
        WlAccess.removeListener(accessChanged);
        super.onFragmentDestroy();
    }

    @Override public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean routeEnabled = WlAccess.enabled() && WlAccess.cached() != null;
        items.add(UItem.asCheck(ENABLE, text(R.string.NM_WL_Enable), text(R.string.NM_WL_About), true)
                .setChecked(routeEnabled)
                .setEnabled(WlAccess.cached() != null));
        items.add(UItem.asHeader(text(R.string.NM_WL_Request)));
        items.add(asSettingsLink(STATUS, IconBackgroundColors.BLUE, R.drawable.msg_info,
                text(R.string.NM_WL_Status), text(statusString())).setEnabled(true));
        WlAccess.Subscription subscription = WlAccess.subscription(account);
        if (!waitingPayment) {
            boolean shared = WlAccess.cached() != null && !WlAccess.hasAccountGrant(account);
            int action = shared ? R.string.NM_WL_RenewSponsor
                    : subscription != null && subscription.expiresAt > 0 ? R.string.NM_WL_Renew : R.string.NM_WL_Activate;
            UItem activateItem = asSettingsLink(PAY, IconBackgroundColors.GREEN, R.drawable.msg_payment_card_solar,
                    text(action)).setEnabled(shared || !"blocked".equals(status));
            items.add(activateItem);
        }
        long expires = WlAccess.accessExpiresAt(account);
        if (expires == 0 && subscription != null) expires = subscription.expiresAt;
        items.add(asPlainSettingsRowWithSubtitle(EXPIRY, text(R.string.NM_WL_Expiry),
                expires > 0 ? LocaleController.formatDate(expires) : text(R.string.NM_WL_NoSubscription))
                .setEnabled(false));
        items.add(asPlainSettingsRowWithSubtitle(PRICE, text(R.string.NM_WL_Price),
                priceString()).setEnabled(false));
        items.add(UItem.asShadow(text(R.string.NM_WL_SharedAccess)));
        for (UItem item : items) {
            if (item.id == STATUS || item.id == PAY || item.id == EXPIRY || item.id == PRICE) {
                int rowAccount = item.id == PRICE
                        ? waitingPayment ? orderAccount : WlAccess.sponsorAccount(account) : account;
                item.dialogId = UserConfig.getInstance(rowAccount).getClientUserId();
                item.object = rowAccount + ":" + WlAccess.accountEpoch(rowAccount);
            }
        }
    }

    @Override public void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null || syncAccount()) { reload(); return; }
        if (item.id == ENABLE) {
            boolean routeEnabled = WlAccess.enabled() && WlAccess.cached() != null;
            if (routeEnabled) {
                activateAfterPayment = false;
                WlAccess.setEnabled(false);
                reload();
            } else if (WlAccess.cached() != null) {
                WlAccess.setEnabled(true);
                reload();
            }
        } else if (item.id == PAY && !waitingPayment
                && (WlAccess.cached() != null || !"blocked".equals(status))) {
            startCheckout();
        }
    }

    private void startCheckout() {
        orderAccount = WlAccess.sponsorAccount(account);
        orderOwner = UserConfig.getInstance(orderAccount).getClientUserId();
        orderEpoch = WlAccess.accountEpoch(orderAccount);
        paymentGeneration++;
        WlAccess.Subscription subscription = WlAccess.subscription(orderAccount);
        startingExpiry = subscription == null ? WlAccess.accessExpiresAt(orderAccount) : subscription.expiresAt;
        activateAfterPayment = true;
        paymentConfirmed = false;
        waitingPayment = true;
        paymentPolls = 0;
        paymentErrors = 0;
        openedUrl = "";
        busy = true;
        status = "checkout";
        reload();
        WlAccess.checkout(orderAccount, checkoutCallback());
    }

    private void pollOrder() {
        busy = true;
        reload();
        WlAccess.checkOrder(orderAccount, order.orderId, checkoutCallback());
    }

    private WlAccess.CheckoutCallback checkoutCallback() {
        int expected = ++generation;
        int expectedPayment = paymentGeneration;
        long expectedUid = owner;
        WeakReference<WlPreferencesActivity> ref = new WeakReference<>(this);
        return (state, checkout) -> {
            WlPreferencesActivity screen = ref.get();
            if (screen == null || screen.paymentGeneration != expectedPayment || screen.owner != expectedUid
                    || UserConfig.selectedAccount != screen.account
                    || WlAccess.accountEpoch(screen.account) != screen.ownerEpoch
                    || UserConfig.getInstance(screen.account).getClientUserId() != expectedUid) return;
            if (!screen.visible || screen.generation != expected) {
                if (!screen.orderCurrent() || "account_changed".equals(state)) {
                    screen.clearPayment();
                    screen.status = "account_changed";
                } else if ("blocked".equals(state)) {
                    screen.clearPayment();
                    screen.status = state;
                } else if (checkout != null) {
                    screen.order = checkout;
                    screen.paymentConfirmed = "completed".equals(state);
                    if ("payment_failed".equals(state)) {
                        screen.clearPayment();
                        screen.status = state;
                    }
                } else if (screen.order == null) {
                    screen.clearPayment();
                    screen.status = state;
                }
                return;
            }
            screen.busy = false;
            if (!screen.orderCurrent() || "account_changed".equals(state)) {
                screen.clearPayment();
                screen.status = "account_changed";
            } else if ("blocked".equals(state)) {
                screen.clearPayment();
                screen.status = state;
            } else if (checkout == null) {
                screen.paymentErrors++;
                screen.status = state;
                if (screen.order == null) screen.clearPayment();
            } else {
                screen.order = checkout;
                screen.paymentErrors = 0;
                if ("completed".equals(state)) {
                    screen.paymentConfirmed = true;
                    screen.refresh();
                    return;
                } else if ("payment_failed".equals(state)) {
                    screen.clearPayment();
                    screen.status = state;
                } else {
                    screen.status = "waiting_payment";
                    if (!checkout.confirmationUrl.isEmpty() && !checkout.confirmationUrl.equals(screen.openedUrl)) {
                        if (WlAccess.isCheckoutUrl(checkout.confirmationUrl) && screen.getParentActivity() != null
                                && Browser.openInExternalBrowser(screen.getParentActivity(), checkout.confirmationUrl, false)) {
                            screen.openedUrl = checkout.confirmationUrl;
                        } else {
                            screen.clearPayment();
                            screen.status = "payment_failed";
                        }
                    }
                }
            }
            screen.reload();
            screen.schedulePaymentPoll();
        };
    }

    private void prepareRefreshStatus() {
        if (waitingPayment && !orderCurrent()) {
            clearPayment();
            status = "account_changed";
        } else if (waitingPayment) {
            status = "waiting_payment";
        } else if ("loading".equals(status)) {
            WlAccess.Subscription subscription = WlAccess.subscription(account);
            if (subscription != null) status = subscription.status;
        }
    }

    private void refresh() {
        if (waitingPayment && !orderCurrent()) {
            clearPayment();
            status = "account_changed";
            reload();
            return;
        }
        busy = true;
        prepareRefreshStatus();
        reload();
        int expected = ++generation;
        long expectedUid = owner;
        WeakReference<WlPreferencesActivity> ref = new WeakReference<>(this);
        WlAccess.refresh(waitingPayment ? orderAccount : account, state -> {
            WlPreferencesActivity screen = ref.get();
            if (screen == null || !screen.screenCurrent(expected, expectedUid)) return;
            screen.busy = false;
            screen.status = state;
            if (screen.waitingPayment && !screen.orderCurrent()) {
                screen.clearPayment();
                screen.status = "account_changed";
            } else if ("blocked".equals(state) || "account_changed".equals(state)) {
                screen.clearPayment();
            } else if (screen.waitingPayment) {
                WlAccess.Subscription paid = WlAccess.subscription(screen.orderAccount);
                if ("approved".equals(state) && paid != null && "approved".equals(paid.status)
                        && WlAccess.hasAccountGrant(screen.orderAccount)
                        && (screen.paymentConfirmed || paid.expiresAt > screen.startingExpiry)) {
                    if (screen.activateAfterPayment) WlAccess.setEnabled(true);
                    screen.clearPayment();
                    screen.status = "completed";
                } else {
                    boolean failed = "error".equals(state) || "busy".equals(state)
                            || "authentication_required".equals(state);
                    if (failed) screen.paymentErrors++;
                    else { screen.paymentErrors = 0; screen.status = "waiting_payment"; }
                }
            }
            screen.reload();
            screen.schedulePaymentPoll();
        });
    }

    private void schedulePaymentPoll() {
        AndroidUtilities.cancelRunOnUIThread(paymentPoll);
        if (!visible || busy || !waitingPayment) return;
        if (paymentPolls >= MAX_PAYMENT_POLLS) {
            status = "payment_delayed";
            reload();
            AndroidUtilities.runOnUIThread(paymentPoll, 60_000L);
        } else {
            AndroidUtilities.runOnUIThread(paymentPoll, 5_000L);
        }
    }

    private void clearPayment() {
        paymentGeneration++;
        AndroidUtilities.cancelRunOnUIThread(paymentPoll);
        order = null;
        waitingPayment = false;
        paymentConfirmed = false;
        activateAfterPayment = false;
        openedUrl = "";
    }

    private int statusString() {
        if ("checkout".equals(status)) return R.string.NM_WL_Checkout;
        if ("loading".equals(status)) return R.string.NM_WL_Checking;
        if ("completed".equals(status)) return R.string.NM_WL_PaymentCompleted;
        if ("waiting_payment".equals(status)) return R.string.NM_WL_WaitingPayment;
        if ("payment_delayed".equals(status)) return R.string.NM_WL_PaymentDelayed;
        if ("payment_failed".equals(status)) return R.string.NM_WL_PaymentFailed;
        if ("error".equals(status)) return R.string.NM_WL_Error;
        if ("busy".equals(status)) return R.string.NM_WL_Busy;
        if ("authentication_required".equals(status)) return R.string.NM_WL_AuthRequired;
        if ("account_changed".equals(status)) return R.string.NM_WL_AccountChanged;
        if ("blocked".equals(status)) return R.string.NM_WL_Blocked;
        if (WlAccess.cached() != null) return WlAccess.hasAccountGrant(account)
                ? R.string.NM_WL_Approved : R.string.NM_WL_OtherAccount;
        if ("expired".equals(status)) return R.string.NM_WL_Expired;
        return R.string.NM_WL_PaymentRequired;
    }

    private static String text(int id) { return LocaleController.getString(id); }
    private String priceString() {
        if (waitingPayment) {
            if (!orderCurrent()) return text(R.string.NM_WL_Checking);
            return order != null && order.amountCents > 0
                    ? LocaleController.getInstance().formatCurrencyString(order.amountCents, "RUB")
                    : text(R.string.NM_WL_Checking);
        }
        if ("error".equals(status) || "busy".equals(status)
                || "authentication_required".equals(status) || "account_changed".equals(status)) {
            return text(R.string.NM_WL_PriceUnknown);
        }
        WlAccess.Subscription plan = WlAccess.subscription(WlAccess.sponsorAccount(account));
        return plan != null && plan.priceCents > 0 && !plan.priceVersion.isEmpty()
                ? LocaleController.formatString(R.string.NM_WL_MonthlyPrice,
                        LocaleController.getInstance().formatCurrencyString(plan.priceCents, "RUB"))
                : text("loading".equals(status) ? R.string.NM_WL_Checking : R.string.NM_WL_PriceUnknown);
    }
    private void reload() { if (listView != null && listView.adapter != null) listView.adapter.update(true); }
}
