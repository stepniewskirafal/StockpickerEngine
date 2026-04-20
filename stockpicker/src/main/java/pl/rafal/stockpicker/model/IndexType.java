package pl.rafal.stockpicker.model;

/**
 * Typ enum reprezentujący trzy główne indeksy GPW używane w naszym screeningu.
 *
 * Używam enum zamiast String dla tych wartości, bo enum daje nam typebezpieczeństwo
 * w czasie kompilacji - nie da się wpisać literówki "WIG40" zamiast "MWIG40",
 * bo kompilator wyłapie to natychmiast. String by to przepuścił i błąd pojawiłby
 * się dopiero w runtime.
 *
 * Nazwa (displayName) jest używana na froncie i w logach. Enum value (nazwa w
 * kodzie) musi być zgodna ze standardem Javy - wielkie litery, bez znaków specjalnych.
 */
public enum IndexType {
    WIG20("WIG20"),
    MWIG40("mWIG40"),
    SWIG80("sWIG80");

    private final String displayName;

    IndexType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
