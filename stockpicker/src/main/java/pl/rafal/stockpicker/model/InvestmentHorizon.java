package pl.rafal.stockpicker.model;

/**
 * Horyzont inwestycyjny - determinuje które wskaźniki techniczne są dla nas
 * najważniejsze przy ocenie spółki.
 *
 * Koncepcja "właściwego horyzontu" jest fundamentalna w analizie technicznej.
 * Każdy wskaźnik ma swój "naturalny" horyzont czasowy - czyli zakres czasu,
 * dla którego daje najbardziej użyteczne sygnały. Nie ma sensu używać
 * SMA(200) tygodniowej do horyzontu jednotygodniowego, tak jak nie ma sensu
 * patrzeć na RSI(14) godzinowy przy horyzoncie dwuletnim.
 *
 * SHORT - 1 miesiąc
 *   Najważniejsze wskaźniki: RSI, MACD cross, SMA(10)
 *   Mniej ważne: SMA(100), SMA(200) - dla tak krótkiego horyzontu
 *   struktura długoterminowa ma mniejsze znaczenie
 *
 * MEDIUM - 1 do 3 miesięcy
 *   Najważniejsze: wszystkie SMA razem, MACD, ZigZag
 *   To klasyczny horyzont dla analizy technicznej W1
 *
 * LONG - 3 do 6 miesięcy
 *   Najważniejsze: SMA(50), SMA(100), SMA(200), struktura trendu z ZigZag
 *   Mniejsza waga dla RSI i MACD, które są wskaźnikami krótszego horyzontu
 */
public enum InvestmentHorizon {
    SHORT("1 miesiąc", 1, 4),
    MEDIUM("1-3 miesiące", 4, 13),
    LONG("3-6 miesięcy", 13, 26);

    private final String displayName;
    private final int minWeeks;
    private final int maxWeeks;

    InvestmentHorizon(String displayName, int minWeeks, int maxWeeks) {
        this.displayName = displayName;
        this.minWeeks = minWeeks;
        this.maxWeeks = maxWeeks;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMinWeeks() {
        return minWeeks;
    }

    public int getMaxWeeks() {
        return maxWeeks;
    }
}
