package pl.rafal.stockpicker.model;

/**
 * Zbiorczy snapshot wszystkich wskaźników technicznych dla jednej spółki
 * w konkretnym momencie czasu (ostatnie zamknięcie tygodniowe).
 *
 * Dlaczego agregujemy w jeden obiekt zamiast trzymać 10 osobnych pól w Stock?
 * Bo separacja odpowiedzialności - Stock opisuje czym jest spółka, a
 * IndicatorSnapshot mówi jakie są jej parametry techniczne teraz. Gdy za tydzień
 * przeliczymy wskaźniki, zmieni się tylko snapshot, a Stock pozostanie ten sam.
 *
 * Wszystkie wartości double poza currentPrice mogą być null jeśli dane są
 * niewystarczające - np. SMA(200) wymaga minimum 200 tygodni historii, co daje
 * prawie 4 lata. Spółki nowo wprowadzone na giełdę tego nie mają.
 */
public record IndicatorSnapshot(
        double currentPrice,      // ostatnie zamknięcie tygodniowe
        Double sma10,             // Double żeby mogło być null dla krótkich serii
        Double sma50,
        Double sma100,
        Double sma200,
        Double smma20,            // Smoothed MA - odporna na outliers
        Double rsi14,             // RSI(14), wartości 0-100
        MacdResult macd,          // MACD ma 3 komponenty, więc osobna struktura
        ZigZagResult zigzag       // ZigZag zwraca ostatnie ekstremum + kierunek
) {
    /**
     * Rekord zagnieżdżony - klasyczny MACD(12, 26, 9).
     * Linia MACD to EMA(12) - EMA(26) dla ceny zamknięcia.
     * Linia sygnału to EMA(9) z linii MACD.
     * Histogram to MACD - sygnał, pokazuje przyspieszenie momentum.
     */
    public record MacdResult(
            Double macdLine,
            Double signalLine,
            Double histogram
    ) {
        /**
         * Świeży byczy cross - najbardziej wartościowy sygnał dla horyzontu 1-3 mies.
         * Zwraca true gdy histogram jest dodatni ale jeszcze niewielki (<10% od zera),
         * co oznacza że cross nastąpił niedawno i jest "świeży".
         */
        public boolean isBullishCross() {
            if (macdLine == null || signalLine == null || histogram == null) return false;
            return macdLine > signalLine && histogram > 0;
        }
    }

    /**
     * ZigZag identyfikuje lokalne szczyty i dołki, filtrując szum cenowy.
     * Użytkownik ustawia próg procentowy (np. 5%) - tylko ruchy większe od
     * tego progu liczą się jako zmiana kierunku.
     */
    public record ZigZagResult(
            double lastPivotPrice,       // cena ostatniego ekstremum
            String lastPivotType,        // "HIGH" lub "LOW"
            boolean higherHighs,         // czy robi higher highs?
            boolean higherLows           // czy robi higher lows?
    ) {
        /**
         * Byczy układ ZigZag = higher highs + higher lows.
         * To potwierdza strukturę trendu wzrostowego.
         */
        public boolean isBullish() {
            return higherHighs && higherLows;
        }
    }

    /**
     * Idealny byczy układ średnich - tzw. "perfect bullish stack".
     * Cena > SMA10 > SMA50 > SMA100 > SMA200.
     * To najsilniejszy sygnał trendu wzrostowego dający komputerowi
     * jednoznaczną odpowiedź: hossa potwierdzona na wszystkich horyzontach.
     */
    public boolean hasPerfectBullishStack() {
        if (sma10 == null || sma50 == null || sma100 == null || sma200 == null) {
            return false;
        }
        return currentPrice > sma10 && sma10 > sma50 && sma50 > sma100 && sma100 > sma200;
    }

    /**
     * Dystans ceny od SMA200 w procentach - miara "rozciągnięcia" trendu.
     * Wartość powyżej 50% ostrzega przed możliwym powrotem do średniej.
     */
    public double distanceFromSma200Percent() {
        if (sma200 == null || sma200 == 0) return 0.0;
        return (currentPrice - sma200) / sma200 * 100;
    }
}
