package app.nimarkogram.messenger.infocards;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.Weather;

import java.lang.ref.WeakReference;

public class WeatherCard extends BaseInfoCard {

    private static final String PLACEHOLDER = "—";

    private boolean needsPermission;
    
    private int requestGeneration;
    private boolean lifecycleAttached;

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
        requestGeneration++;
        super.onAttachedToWindow();
    }

    @Override
    public void onUpdateData(boolean force) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        final int generation = ++requestGeneration;
        final WeakReference<WeatherCard> cardRef = new WeakReference<>(this);
        double[] custom = customLocation();
        if (custom != null) {
            
            showWeatherState();
            Weather.State cached = Weather.getCached();
            if (cached != null) setText(render(cached), true); else startLoading();
            Weather.fetch(custom[0], custom[1], state -> {
                WeatherCard card = cardRef.get();
                if (card != null) card.onFetched(generation, state);
            });
            return;
        }

        if (!hasLocationPermission()) {
            
            showGrantState();
            return;
        }

        showWeatherState();
        Weather.State cached = Weather.getCached();
        if (cached != null) setText(render(cached), true); else startLoading();
        Weather.fetch(false, state -> {
            WeatherCard card = cardRef.get();
            if (card != null) card.onFetched(generation, state);
        });
    }

    private void onFetched(int generation, Weather.State state) {
        if (!lifecycleAttached || generation != requestGeneration || !isAttachedToWindow()) {
            return;
        }
        if (state != null) {
            showWeatherState();
            setText(render(state), true);
        } else if (Weather.getCached() == null) {
            setText(PLACEHOLDER, true);
        }
        stopLoading();
        markDataUpdated();
    }

    private void showWeatherState() {
        needsPermission = false;
        setIconVisible(false);
    }

    private void showGrantState() {
        needsPermission = true;
        stopLoading();
        setIcon(R.drawable.msg_location_solar);   
        setText(LocaleController.getString(R.string.NM_CARDS_GrantLocation), true);
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
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        if (needsPermission) {
            
            startLoading();
            final int generation = ++requestGeneration;
            final WeakReference<WeatherCard> cardRef = new WeakReference<>(this);
            Weather.fetch(true, state -> {
                WeatherCard card = cardRef.get();
                if (card == null || !card.lifecycleAttached
                        || generation != card.requestGeneration || !card.isAttachedToWindow()) {
                    return;
                }
                if (state != null) {
                    card.showWeatherState();
                    card.setText(render(state), true);
                    card.markDataUpdated();
                } else if (!hasLocationPermission()) {
                    card.showGrantState();   
                }
                card.stopLoading();
            });
            return;
        }
        onUpdateData(true); 
    }

    @Override
    protected void onDetachedFromWindow() {
        
        lifecycleAttached = false;
        requestGeneration++;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onCardLongClicked() {
        return false;
    }

    @Override
    public void updateColors() {
        setTextColor(0xffffffff);
    }
}
