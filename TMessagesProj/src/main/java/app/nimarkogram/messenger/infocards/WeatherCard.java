package app.nimarkogram.messenger.infocards;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
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

    
    private int requestGeneration;
    private boolean lifecycleAttached;
    private boolean requestPending;
    private Runnable cancelFetch;

    public WeatherCard(Context context, Theme.ResourcesProvider resourcesProvider, int iconRes) {
        super(context, resourcesProvider);
        
        setIconVisible(false);
        Weather.State cached = Weather.getCached();
        if (cached != null) {
            setText(render(cached), false);
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
        return 1800000; 
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
        final WeakReference<WeatherCard> cardRef = new WeakReference<>(this);
        double[] custom = customLocation();
        if (custom == null && !hasLocationPermission()) {
            showGrantState();
            if (!userInitiated) return;
            startLoading();
        } else {
            
            showWeatherState();
            Weather.State cached = Weather.getCached();
            if (cached != null) setText(render(cached), true); else startLoading();
        }

        requestPending = true;
        Utilities.Callback<Weather.State> callback = state -> {
            WeatherCard card = cardRef.get();
            if (card != null) card.onFetched(generation, account, state);
        };
        Runnable cancel = custom != null
                ? Weather.fetch(custom[0], custom[1], callback)
                : Weather.fetchCancellable(userInitiated, callback);
        if (requestPending && generation == requestGeneration) {
            cancelFetch = cancel;
        } else if (cancel != null) {
            cancel.run();
        }
    }

    private void onFetched(int generation, int account, Weather.State state) {
        if (!lifecycleAttached || generation != requestGeneration || account != UserConfig.selectedAccount
                || !isAttachedToWindow()) {
            return;
        }
        requestPending = false;
        cancelFetch = null;
        if (state != null) {
            showWeatherState();
            setText(render(state), true);
            markDataUpdated();
        } else if (customLocation() == null && !hasLocationPermission()) {
            showGrantState();
        } else {
            showWeatherState();
            Weather.State cached = Weather.getCached();
            setText(cached != null ? render(cached) : PLACEHOLDER, true);
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
        setIcon(R.drawable.msg_location_solar);   
        setText(LocaleController.getString(R.string.NM_CARDS_NameWeather), true);
        setContentDescription(LocaleController.getString(R.string.NM_CARDS_NameWeather) + ": "
                + LocaleController.getString(R.string.NM_CARDS_GrantLocation));
    }

    public static CharSequence liveValueText() {
        Weather.State cached = Weather.getCached();
        return cached != null ? render(cached) : null;
    }

    private static String render(Weather.State state) {
        String emoji = state.getEmoji();
        String temp = state.getTemperature();
        if (temp == null || temp.isEmpty()) return PLACEHOLDER;
        if (emoji == null || emoji.isEmpty()) return temp;
        return emoji + " " + temp;
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
