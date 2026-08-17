package app.nimarkogram.messenger.infocards;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;

import app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity;

public class CryptoCard extends BaseInfoCard {

    private static final String AUTO = "AUTO";

    private static final String[] TARGET_CURRENCIES = {
            AUTO, "USD", "EUR", "RUB", "GBP", "UAH", "KZT", "PLN", "TRY", "CNY", "JPY", "BYN", "INR"
    };

    private static final class CurrencyInfo {
        final String symbol; 
        final boolean suffix; 
        CurrencyInfo(String symbol, boolean suffix) {
            this.symbol = symbol;
            this.suffix = suffix;
        }
    }

    private static final java.util.HashSet<String> AMBIGUOUS_SYMBOLS =
            new java.util.HashSet<>(java.util.Arrays.asList("$", "kr", "Fr", "₩"));

    private static final HashMap<String, CurrencyInfo> CURRENCIES = new HashMap<>();
    static {
        
        CURRENCIES.put("USD", new CurrencyInfo("$", false));
        CURRENCIES.put("EUR", new CurrencyInfo(null, false));
        CURRENCIES.put("RUB", new CurrencyInfo("₽", true));
        CURRENCIES.put("GBP", new CurrencyInfo(null, false));
        CURRENCIES.put("KZT", new CurrencyInfo("₸", true));
        CURRENCIES.put("TRY", new CurrencyInfo("₺", true));
        CURRENCIES.put("UAH", new CurrencyInfo("₴", true));
        CURRENCIES.put("PLN", new CurrencyInfo("zł", true));
        CURRENCIES.put("AED", new CurrencyInfo(null, false));
        CURRENCIES.put("CNY", new CurrencyInfo("CN¥", false));
        CURRENCIES.put("JPY", new CurrencyInfo(null, false));
        CURRENCIES.put("BYN", new CurrencyInfo("Br", true));
        CURRENCIES.put("INR", new CurrencyInfo("₹", false));
    }

    private final int pillId;
    private final String coinKey; 
    private final int iconRes;

    private boolean errorState;

    public CryptoCard(Context context, Theme.ResourcesProvider resourcesProvider,
                      int pillId, String coinKeyOrNull, int iconRes) {
        super(context, resourcesProvider);
        this.pillId = pillId;
        this.coinKey = coinKeyOrNull;
        this.iconRes = iconRes;
        setIcon(iconRes);
    }

    @Override
    public int getCardId() {
        return pillId;
    }

    @Override
    public long getRefreshInterval() {
        return 90000; 
    }

    private boolean firstPaint = true;

    private static final long WARMUP_MS = 1500;
    private boolean warmupDone;
    private boolean lifecycleAttached;
    private long lifecycleGeneration;
    private InfoCardRates.CallbackHandle ratesCallback;
    private final Runnable warmupRunnable = () -> requestRates(true);

    @Override
    protected void onAttachedToWindow() {
        lifecycleAttached = true;
        lifecycleGeneration++;
        super.onAttachedToWindow();
    }

    @Override
    public void onUpdateData(boolean force) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        
        if (InfoCardRates.hasCached()) {
            render(!firstPaint);
            firstPaint = false;
            requestRates(force);
            return;
        }
        
        startLoading();
        firstPaint = false;
        if (!warmupDone && !force) {
            
            warmupDone = true;
            removeCallbacks(warmupRunnable);
            postDelayed(warmupRunnable, WARMUP_MS);
        } else {
            requestRates(force);
        }
    }

    private void requestRates(boolean force) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        if (ratesCallback != null) ratesCallback.cancel();
        final long generation = lifecycleGeneration;
        ratesCallback = InfoCardRates.fetchWeak(force, () -> {
            if (lifecycleAttached && generation == lifecycleGeneration && isAttachedToWindow()) {
                ratesCallback = null;
                render(true);
            }
        });
    }

    private void render(boolean animated) {
        
        if (!InfoCardRates.hasCached()) {
            setErrorState();
            return;
        }
        errorState = false;
        removeCallbacks(retryRunnable); 
        retryAttempt = 0;              
        setIcon(iconRes);
        
        String ccy = resolveCurrency(InfoCardsConfig.getTargetCurrency(getCardId()));
        double value;
        if (coinKey == null) {
            value = InfoCardRates.fiatRate(ccy);       
        } else {
            value = InfoCardRates.coinInFiat(coinKey, ccy);
        }
        setText(format(value, ccy), animated);
        stopLoading();
        markDataUpdated();
    }

    private static final long RETRY_BASE_MS = 1500;
    private static final long RETRY_MAX_MS = 60_000;
    
    private static final int RETRY_SHOW_AFTER = 3;
    private int retryAttempt = 0;
    private final Runnable retryRunnable = () -> {
        if (lifecycleAttached && isAttachedToWindow() && errorState) onUpdateData(true);
    };

    private void setErrorState() {
        errorState = true;
        if (retryAttempt < RETRY_SHOW_AFTER) {
            
            startLoading();
        } else {
            setIcon(R.drawable.msg_retry);
            setText(LocaleController.getString(R.string.Retry), true);
            stopLoading();
        }
        removeCallbacks(retryRunnable);
        
        long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(retryAttempt, 16));
        postDelayed(retryRunnable, delay);
        retryAttempt++;
    }

    @Override
    protected void onDetachedFromWindow() {
        lifecycleAttached = false;
        lifecycleGeneration++;
        removeCallbacks(retryRunnable);
        removeCallbacks(warmupRunnable);
        if (ratesCallback != null) {
            ratesCallback.cancel();
            ratesCallback = null;
        }
        super.onDetachedFromWindow();
    }

    private static String format(double value, String ccy) {
        String iso = ccy == null ? "USD" : ccy.trim().toUpperCase(Locale.ROOT);

        int exp = Math.max(0, BillingController.getInstance().getCurrencyExp(iso));
        BigDecimal scaled = BigDecimal.valueOf(value).setScale(exp, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setGroupingUsed(true);
        nf.setMinimumFractionDigits(exp);
        nf.setMaximumFractionDigits(exp);
        String amount = nf.format(scaled);

        CurrencyInfo info = CURRENCIES.get(iso);
        String symbol = info != null ? info.symbol : null; 
        boolean custom = symbol != null;                   
        if (!custom) {
            try {
                symbol = Currency.getInstance(iso).getSymbol(Locale.US);
            } catch (Exception ignore) {
            }
        }

        if (symbol != null && !symbol.isEmpty() && !symbol.equalsIgnoreCase(iso)) {
            
            if (!custom && AMBIGUOUS_SYMBOLS.contains(symbol)) {
                return amount + " " + iso;
            }
            
            if (info == null || !info.suffix) {
                return symbol + amount;
            }
            return amount + " " + symbol;
        }
        
        return amount + " " + iso;
    }

    @Override
    protected boolean isBranded() {
        return true; 
    }

    @Override
    public void onCardClicked() {
        
        if (errorState) {
            onUpdateData(true);
        } else {
            onCardLongClicked();
        }
    }

    @Override
    public boolean onCardLongClicked() {
        BaseFragment fragment = getCurrentFragment();
        if (fragment == null) {
            return false;
        }
        
        final String stored = InfoCardsConfig.getTargetCurrency(getCardId());
        final ItemOptions options = ItemOptions.makeOptions(fragment, this);
        options.add(R.drawable.msg_language, menuCurrencyLabel(stored),
                () -> AndroidUtilities.runOnUIThread(this::showCurrencyPicker));
        options.addGap();
        options.add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true));
        options.add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                () -> fragment.presentFragment(new InfoCardsPreferencesActivity()));
        options.setGravity(LocaleController.isRTL ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT)
                .show();
        return true;
    }

    private void showCurrencyPicker() {
        BaseFragment fragment = getCurrentFragment();
        if (fragment == null) return;
        final String stored = InfoCardsConfig.getTargetCurrency(getCardId());
        final ItemOptions picker = ItemOptions.makeOptions(fragment, this);
        for (final String ccy : TARGET_CURRENCIES) {
            picker.addChecked(ccy.equalsIgnoreCase(stored), currencyLabel(ccy), () -> {
                if (!ccy.equalsIgnoreCase(stored)) {
                    InfoCardsConfig.setTargetCurrency(getCardId(), ccy);
                }
            });
        }
        picker.setGravity(LocaleController.isRTL ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT)
                .setMaxHeight(AndroidUtilities.dp(320))
                .show();
    }

    public static CharSequence liveValueText(int cardId) {
        if (!InfoCardRates.hasCached()) return null;
        final String coin;
        if (cardId == InfoCardType.TON.id) coin = "ton";
        else if (cardId == InfoCardType.BTC.id) coin = "btc";
        else coin = null; 
        String ccy = resolveCurrency(InfoCardsConfig.getTargetCurrency(cardId));
        double value = coin != null ? InfoCardRates.coinInFiat(coin, ccy) : InfoCardRates.fiatRate(ccy);
        if (Double.isNaN(value) || value <= 0) return null;
        return format(value, ccy);
    }

    private static String resolveCurrency(String stored) {
        String sel = stored == null ? AUTO : stored.trim().toUpperCase(Locale.ROOT);
        if (AUTO.equals(sel)) {
            try {
                String local = Currency.getInstance(Locale.getDefault()).getCurrencyCode();
                if (local != null && isValidCurrency(local)) {
                    return local.toUpperCase(Locale.ROOT);
                }
            } catch (Throwable ignore) {
            }
            return "USD";
        }
        return isValidCurrency(sel) ? sel : "USD";
    }

    private static boolean isValidCurrency(String iso) {
        try {
            Currency.getInstance(iso.toUpperCase(Locale.ROOT));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static CharSequence menuCurrencyLabel(String stored) {
        if (stored == null || AUTO.equalsIgnoreCase(stored)) {
            return LocaleController.getString(R.string.QualityAuto) + " · " + resolveCurrency(AUTO);
        }
        return currencyLabel(stored);
    }

    private static CharSequence currencyLabel(String ccy) {
        if (ccy == null) {
            return "USD";
        }
        if (AUTO.equalsIgnoreCase(ccy)) {
            return LocaleController.getString(R.string.QualityAuto);
        }
        String iso = ccy.trim().toUpperCase(Locale.ROOT);
        CurrencyInfo info = CURRENCIES.get(iso);
        String symbol = info != null ? info.symbol : null;
        if (symbol == null) {
            try {
                symbol = Currency.getInstance(iso).getSymbol(Locale.US);
            } catch (Exception ignore) {
            }
        }
        if (symbol != null && !symbol.isEmpty() && !symbol.equalsIgnoreCase(iso)) {
            return symbol + " — " + iso;
        }
        return iso;
    }

    private static BaseFragment getCurrentFragment() {
        try {
            LaunchActivity la = LaunchActivity.instance;
            if (la != null) {
                INavigationLayout layout = la.getActionBarLayout();
                if (layout != null) {
                    return layout.getLastFragment();
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    @Override
    public void updateColors() {
        setTextColor(0xffffffff);
    }
}
