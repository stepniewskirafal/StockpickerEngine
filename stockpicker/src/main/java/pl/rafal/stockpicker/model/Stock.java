package pl.rafal.stockpicker.model;

/**
 * Reprezentuje spółkę giełdową na GPW z jej metadanymi.
 *
 * Nie zawiera danych cenowych (świec) - te ładujemy osobno w momencie analizy.
 * Dzięki temu lista wszystkich spółek jest lekka i może być załadowana raz
 * do pamięci przy starcie aplikacji.
 *
 * Pole 'index' to enum określający przynależność do WIG20/mWIG40/sWIG80.
 * To pozwala użytkownikowi wybrać zakres analizy w formularzu.
 *
 * Pole 'tickerSuffix' używamy przy budowaniu URL-a stooq. Dla GPW zwykle
 * nic się nie dodaje, ale niektóre systemy wymagają .WA (Yahoo), .PL itd.
 */
public record Stock(
        String ticker,         // np. "XTB", "ALE"
        String name,           // np. "X-Trade Brokers"
        IndexType index,       // do którego indeksu należy
        String sector          // sektor dla późniejszej filtracji
) {
    public Stock {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker nie może być pusty");
        }
        if (index == null) {
            throw new IllegalArgumentException("Index musi być określony");
        }
    }

    /**
     * Zwraca ticker w formacie używanym przez stooq.pl - lowercase bez sufiksów.
     * Stooq używa po prostu kodu GPW małymi literami.
     */
    public String stooqSymbol() {
        return ticker.toLowerCase();
    }
}
