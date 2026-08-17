package app.nimarkogram.messenger.plugins;

public final class AppEvent {

    public final String value;
     
    public final String name;

    private AppEvent(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public static final AppEvent START = new AppEvent("START", PluginsConstants.APP_START);
    public static final AppEvent STOP = new AppEvent("STOP", PluginsConstants.APP_STOP);
    public static final AppEvent PAUSE = new AppEvent("PAUSE", PluginsConstants.APP_PAUSE);
    public static final AppEvent RESUME = new AppEvent("RESUME", PluginsConstants.APP_RESUME);

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int hashCode() {
        return value == null ? 0 : value.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (other == this) {
            return true;
        }
        if (other instanceof AppEvent) {
            AppEvent o = (AppEvent) other;
            return value != null && value.equals(o.value);
        }
        
        String s = String.valueOf(other);
        if (s == null) {
            return false;
        }
        if (value != null && value.equals(s)) {
            return true;
        }
        if (name != null) {
            return name.equals(s)
                    || s.equals("AppEvent." + name)
                    || s.endsWith("." + name);
        }
        return false;
    }
}
