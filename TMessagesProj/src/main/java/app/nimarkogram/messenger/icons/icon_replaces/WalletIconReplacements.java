/*
 * Derived from Telegram for Android drawable geometry (GPL v2 or later).
 * Modifications Copyright (C) 2026 Ettacent
 */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons.icon_replaces;

import org.telegram.messenger.R;

final class WalletIconReplacements {
    private WalletIconReplacements() {}

    static void apply(BaseIconReplace pack, boolean liquid) {
        add(pack, R.drawable.scan_qr, liquid ? R.drawable.ng_wallet_scan_qr_liquid : R.drawable.ng_wallet_scan_qr_plumpy);
        add(pack, R.drawable.wallet_apps, liquid ? R.drawable.ng_wallet_wallet_apps_liquid : R.drawable.ng_wallet_wallet_apps_plumpy);
        add(pack, R.drawable.wallet_comment_locked, liquid ? R.drawable.ng_wallet_wallet_comment_locked_liquid : R.drawable.ng_wallet_wallet_comment_locked_plumpy);
        add(pack, R.drawable.wallet_comment_unlocked, liquid ? R.drawable.ng_wallet_wallet_comment_unlocked_liquid : R.drawable.ng_wallet_wallet_comment_unlocked_plumpy);
        add(pack, R.drawable.wallet_protect, liquid ? R.drawable.ng_wallet_wallet_protect_liquid : R.drawable.ng_wallet_wallet_protect_plumpy);
        add(pack, R.drawable.wallet_transaction_key, liquid ? R.drawable.ng_wallet_wallet_transaction_key_liquid : R.drawable.ng_wallet_wallet_transaction_key_plumpy);
        add(pack, R.drawable.wallet_buy, liquid ? R.drawable.ng_wallet_wallet_buy_liquid : R.drawable.ng_wallet_wallet_buy_plumpy);
        add(pack, R.drawable.wallet_sell, liquid ? R.drawable.ng_wallet_wallet_sell_liquid : R.drawable.ng_wallet_wallet_sell_plumpy);
        add(pack, R.drawable.wallet_transfer, liquid ? R.drawable.ng_wallet_wallet_transfer_liquid : R.drawable.ng_wallet_wallet_transfer_plumpy);
        add(pack, R.drawable.wallet_currency_exchange, liquid ? R.drawable.ng_wallet_wallet_currency_exchange_liquid : R.drawable.ng_wallet_wallet_currency_exchange_plumpy);
        add(pack, R.drawable.wallet_transaction_in, liquid ? R.drawable.ng_wallet_wallet_transaction_in_liquid : R.drawable.ng_wallet_wallet_transaction_in_plumpy);
        add(pack, R.drawable.wallet_transaction_out, liquid ? R.drawable.ng_wallet_wallet_transaction_out_liquid : R.drawable.ng_wallet_wallet_transaction_out_plumpy);
        add(pack, R.drawable.wallet_transaction_topup, liquid ? R.drawable.ng_wallet_wallet_transaction_topup_liquid : R.drawable.ng_wallet_wallet_transaction_topup_plumpy);
        add(pack, R.drawable.wallet_nft, liquid ? R.drawable.ng_wallet_wallet_nft_liquid : R.drawable.ng_wallet_wallet_nft_plumpy);
        add(pack, R.drawable.wallet_intro_chats, liquid ? R.drawable.ng_wallet_wallet_intro_chats_liquid : R.drawable.ng_wallet_wallet_intro_chats_plumpy);
        add(pack, R.drawable.wallet_intro_store, liquid ? R.drawable.ng_wallet_wallet_intro_store_liquid : R.drawable.ng_wallet_wallet_intro_store_plumpy);
        add(pack, R.drawable.wallet_intro_trade, liquid ? R.drawable.ng_wallet_wallet_intro_trade_liquid : R.drawable.ng_wallet_wallet_intro_trade_plumpy);
        add(pack, R.drawable.wallet_learn_fees, liquid ? R.drawable.ng_wallet_wallet_learn_fees_liquid : R.drawable.ng_wallet_wallet_learn_fees_plumpy);
        add(pack, R.drawable.wallet_learn_hidden, liquid ? R.drawable.ng_wallet_wallet_learn_hidden_liquid : R.drawable.ng_wallet_wallet_learn_hidden_plumpy);
        add(pack, R.drawable.wallet_learn_instant, liquid ? R.drawable.ng_wallet_wallet_learn_instant_liquid : R.drawable.ng_wallet_wallet_learn_instant_plumpy);
        add(pack, R.drawable.wallet_learn_protected, liquid ? R.drawable.ng_wallet_wallet_learn_protected_liquid : R.drawable.ng_wallet_wallet_learn_protected_plumpy);
        add(pack, R.drawable.wallet_learn_verified, liquid ? R.drawable.ng_wallet_wallet_learn_verified_liquid : R.drawable.ng_wallet_wallet_learn_verified_plumpy);
        add(pack, R.drawable.wallet_learn_warn, liquid ? R.drawable.ng_wallet_wallet_learn_warn_liquid : R.drawable.ng_wallet_wallet_learn_warn_plumpy);
        add(pack, R.drawable.wallet_globe, liquid ? R.drawable.ng_wallet_wallet_globe_liquid : R.drawable.ng_wallet_wallet_globe_plumpy);
    }

    private static void add(BaseIconReplace pack, int source, int target) {
        pack.replaces.putIfAbsent(source, target);
    }
}
