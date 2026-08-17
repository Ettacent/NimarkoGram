package app.nimarkogram.messenger.infocards;

public enum InfoCardType {
    WEATHER(1),
    TON(2),
    BTC(3),
    USD(4),
    CACHE(5),
    PROXY(6);

    public final int id;

    InfoCardType(int id) {
        this.id = id;
    }

    public static InfoCardType byId(int id) {
        for (InfoCardType t : values()) {
            if (t.id == id) return t;
        }
        return null;
    }
}
