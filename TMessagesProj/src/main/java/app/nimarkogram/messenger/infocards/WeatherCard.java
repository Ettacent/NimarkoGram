/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.infocards;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.Weather;

import java.lang.ref.WeakReference;

public class WeatherCard extends BaseInfoCard implements NotificationCenter.NotificationCenterDelegate {

    private static final String PLACEHOLDER = "—";
    private static AbsoluteSizeSpan weatherEmojiSize;

    private static CachedWeather cachedWeather;
    private static String automaticLocationKey;

    private static final class CachedWeather {
        final int account;
        final long owner;
        final String source;
        final String locationKey;
        final Weather.State state;

        CachedWeather(int account, long owner, String source, Weather.State state) {
            this.account = account;
            this.owner = owner;
            this.source = source;
            this.state = state;
            this.locationKey = locationKey(account, owner, state.lat, state.lng);
        }
    }

    private static String locationKey(int account, long owner, double lat, double lng) {
        if (Double.isNaN(lat) || Double.isInfinite(lat) || Math.abs(lat) > 90
                || Double.isNaN(lng) || Double.isInfinite(lng) || Math.abs(lng) > 180) return null;
        return account + ":" + owner + ":" + Math.round(lat * 1000) + ":" + Math.round(lng * 1000);
    }

    private static boolean ownsWeather(int account, long owner, String source) {
        return owner != 0 && account == UserConfig.selectedAccount
                && UserConfig.getInstance(account).isClientActivated()
                && UserConfig.getInstance(account).getClientUserId() == owner
                && java.util.Objects.equals(source, InfoCardsConfig.getCustomWeatherLocation());
    }

    private static Weather.State currentWeather() {
        CachedWeather value = cachedWeather;
        return value != null && ownsWeather(value.account, value.owner, value.source)
                && (customLocation() != null || hasLocationPermission()
                    && value.locationKey != null && value.locationKey.equals(automaticLocationKey)) ? value.state : null;
    }
    private int requestGeneration;
    private boolean lifecycleAttached;
    private boolean requestPending;
    private Runnable cancelFetch;

    public WeatherCard(Context context, Theme.ResourcesProvider resourcesProvider, int iconRes) {
        super(context, resourcesProvider);
        setIconVisible(false);
        Weather.State cached = currentWeather();
        if (cached != null) {
            setText(renderCardValue(cached), false);
        } else {
            setText(PLACEHOLDER, false);
        }
    }

    @Override
    public int getCardId() {
        return InfoCardType.WEATHER.id;
    }

    @Override
    public long getRefreshInterval() {
        return 1800000; // 30 minutes
    }

    @Override
    protected void onAttachedToWindow() {
        lifecycleAttached = true;
        cancelRequest();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        super.onAttachedToWindow();
    }

    @Override
    public void onUpdateData(boolean force) {
        updateWeather(false);
    }
    private void updateWeather(boolean userInitiated) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        cancelRequest();
        final int generation = requestGeneration;
        final int account = UserConfig.selectedAccount;
        final long owner = UserConfig.getInstance(account).getClientUserId();
        final String source = InfoCardsConfig.getCustomWeatherLocation();
        final WeakReference<WeatherCard> cardRef = new WeakReference<>(this);
        double[] custom = customLocation();
        if (custom == null) automaticLocationKey = null;
        if (custom == null && !hasLocationPermission()) {
            showGrantState();
            if (!userInitiated) return;
            startLoading();
        } else {
            showWeatherState();
            Weather.State cached = currentWeather();
            if (cached != null) {
                setText(renderCardValue(cached), true);
            } else {
                setText(PLACEHOLDER, true);
                startLoading();
            }
        }

        requestPending = true;
        Utilities.Callback<Weather.State> callback = state -> {
            WeatherCard card = cardRef.get();
            if (card != null) card.onFetched(generation, account, owner, source, state);
        };
        Runnable cancel = custom != null
                ? Weather.fetch(custom[0], custom[1], callback)
                : Weather.fetchCancellable(userInitiated, callback, location -> {
                    WeatherCard card = cardRef.get();
                    if (card != null) card.onLocated(generation, account, owner, source, location);
                });
        if (requestPending && generation == requestGeneration) {
            cancelFetch = cancel;
        } else if (cancel != null) {
            cancel.run();
        }
    }

    private void onLocated(int generation, int account, long owner, String source, Location location) {
        if (!lifecycleAttached || generation != requestGeneration || !ownsWeather(account, owner, source)
                || !isAttachedToWindow()) return;
        automaticLocationKey = location == null ? null
                : locationKey(account, owner, location.getLatitude(), location.getLongitude());
        Weather.State cached = currentWeather();
        setText(cached != null ? renderCardValue(cached) : PLACEHOLDER, true);
    }

    private void onFetched(int generation, int account, long owner, String source, Weather.State state) {
        if (!lifecycleAttached || generation != requestGeneration || !ownsWeather(account, owner, source)
                || !isAttachedToWindow()) {
            return;
        }
        requestPending = false;
        cancelFetch = null;
        if (state != null && customLocation() == null) {
            String key = locationKey(account, owner, state.lat, state.lng);
            if (key == null || !key.equals(automaticLocationKey)) state = null;
        }
        if (state != null) {
            cachedWeather = new CachedWeather(account, owner, source, state);
            showWeatherState();
            setText(renderCardValue(state), true);
            markDataUpdated();
        } else if (customLocation() == null && !hasLocationPermission()) {
            showGrantState();
        } else {
            showWeatherState();
            Weather.State cached = currentWeather();
            setText(cached != null ? renderCardValue(cached) : PLACEHOLDER, true);
        }
        stopLoading();
    }
    private void cancelRequest() {
        requestGeneration++;
        requestPending = false;
        Runnable cancel = cancelFetch;
        cancelFetch = null;
        if (cancel != null) cancel.run();
        stopLoading();
    }

    private void showWeatherState() {
        setIconVisible(false);
    }

    private void showGrantState() {
        stopLoading();
        setIcon(R.drawable.msg_location_solar);   // setIcon also makes the icon slot visible
        setText(LocaleController.getString(R.string.NM_CARDS_NameWeather), true);
        setContentDescription(LocaleController.getString(R.string.NM_CARDS_NameWeather) + ": "
                + LocaleController.getString(R.string.NM_CARDS_GrantLocation));
    }

    public static CharSequence liveValueText() {
        Weather.State cached = currentWeather();
        return cached != null ? render(cached) : null;
    }

    private static String render(Weather.State state) {
        String emoji = state.getEmoji();
        String temp = state.getTemperature();
        if (temp == null || temp.isEmpty()) return PLACEHOLDER;
        if (emoji == null || emoji.isEmpty()) return temp;
        return emoji + " " + temp;
    }

    private static CharSequence renderCardValue(Weather.State state) {
        String value = render(state);
        String emoji = state.getEmoji();
        if (PLACEHOLDER.equals(value) || emoji == null || emoji.isEmpty()) return value;
        int size = AndroidUtilities.dp(16);
        if (weatherEmojiSize == null || weatherEmojiSize.getSize() != size) {
            weatherEmojiSize = new AbsoluteSizeSpan(size);
        }
        SpannableString result = new SpannableString(value);
        result.setSpan(weatherEmojiSize, 0, emoji.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }
    private static boolean hasLocationPermission() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static double[] customLocation() {
        String json = InfoCardsConfig.getCustomWeatherLocation();
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("lat") && o.has("lng")) {
                return new double[]{o.getDouble("lat"), o.getDouble("lng")};
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    @Override
    public void onCardClicked() {
        updateWeather(true);
    }
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            cancelRequest();
            onUpdateData(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        lifecycleAttached = false;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        cancelRequest();
        super.onDetachedFromWindow();
    }

    @Override
    public void updateColors() {
        setTextColor(0xffffffff);
    }
}
