package pl.rafal.stockpicker.service.indicator;

import pl.rafal.stockpicker.model.Candle;
import pl.rafal.stockpicker.model.IndicatorSnapshot.ZigZagResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Kalkulator ZigZag - identyfikator znaczących szczytów i dołków.
 *
 * ZigZag to filtr strukturalny, nie oscylator. Jego zadaniem jest pokazać
 * szkielet ruchu ceny bez szumu dziennego. Wyobraź sobie że bierzesz gumkę
 * i usuwasz wszystkie ruchy mniejsze niż X procent - zostaje Ci ZigZag.
 *
 * Algorytm działa tak:
 * 1. Idziemy od początku historii, szukając pierwszego lokalnego ekstremum
 * 2. Gdy znajdziemy szczyt, szukamy dołka oddalonego o minimum threshold%
 * 3. Gdy znajdziemy dołek, szukamy szczytu oddalonego o minimum threshold%
 * 4. Naprzemiennie, aż przejdziemy całą historię
 *
 * Rezultat to lista PIVOTów (punktów zwrotnych), z których bezpośrednio
 * widać strukturę trendu:
 *   - Higher Highs + Higher Lows = trend wzrostowy (bycze)
 *   - Lower Highs + Lower Lows = trend spadkowy (niedźwiedzie)
 *   - Sideways = trend boczny (konsolidacja)
 *
 * Zastosowanie w naszym silniku: potwierdzenie że trend ma zdrową strukturę,
 * a nie jest jedynie szybkim wyskokiem ceny.
 *
 * Parametr threshold typowo 5% dla interwału tygodniowego na akcjach GPW.
 * Dla bardziej zmiennych instrumentów (krypto, małe spółki) użylibyśmy 10%.
 */
public final class ZigZagCalculator {

    private ZigZagCalculator() {}

    /**
     * Reprezentuje jeden punkt zwrotny (pivot) w strukturze ZigZag.
     * To klasa prywatna bo jest używana tylko wewnątrz kalkulacji.
     */
    private record Pivot(int index, double price, boolean isHigh) {}

    /**
     * Analizuje serię świec i zwraca charakterystykę struktury trendu.
     *
     * @param candles lista świec (OHLCV) chronologicznie
     * @param thresholdPercent minimalny procent zmiany (np. 5.0 dla 5%)
     * @return ZigZagResult z informacją o ostatnim pivocie i strukturze trendu
     */
    public static ZigZagResult zigzag(List<Candle> candles, double thresholdPercent) {
        // Potrzebujemy minimum kilku świec żeby mieć szansę na znalezienie
        // sensownej struktury. Dla interwału tygodniowego 20 świec to ~5 miesięcy.
        if (candles == null || candles.size() < 20 || thresholdPercent <= 0) {
            return new ZigZagResult(0, "UNKNOWN", false, false);
        }

        double thresholdRatio = thresholdPercent / 100.0;

        // Algorytm ZigZag używa zasady "ostatniego potwierdzonego kierunku".
        // Startujemy od pierwszej świecy jako tymczasowego pivota i szukamy,
        // czy kolejne świece łamią próg w którąś stronę.
        List<Pivot> pivots = new ArrayList<>();
        double currentExtremeHigh = candles.get(0).high();
        double currentExtremeLow = candles.get(0).low();
        int currentExtremeHighIdx = 0;
        int currentExtremeLowIdx = 0;
        Boolean trendUp = null; // null = jeszcze nie określony

        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);

            if (trendUp == null) {
                // Faza odkrywania pierwszego kierunku
                // Aktualizujemy oba ekstrema i czekamy aż któreś przekroczy próg
                if (c.high() > currentExtremeHigh) {
                    currentExtremeHigh = c.high();
                    currentExtremeHighIdx = i;
                }
                if (c.low() < currentExtremeLow) {
                    currentExtremeLow = c.low();
                    currentExtremeLowIdx = i;
                }
                // Gdy odległość między ekstremami przekroczy próg, deklarujemy trend
                double range = (currentExtremeHigh - currentExtremeLow) / currentExtremeLow;
                if (range >= thresholdRatio) {
                    // Kolejność indeksów decyduje: co było wcześniej, to jest pierwszym pivotem
                    if (currentExtremeLowIdx < currentExtremeHighIdx) {
                        pivots.add(new Pivot(currentExtremeLowIdx, currentExtremeLow, false));
                        trendUp = true;
                    } else {
                        pivots.add(new Pivot(currentExtremeHighIdx, currentExtremeHigh, true));
                        trendUp = false;
                    }
                }
            } else if (trendUp) {
                // Trend wzrostowy - śledzimy najwyższy szczyt.
                // Zwrot następuje gdy cena spadnie o threshold% od aktualnego high.
                if (c.high() > currentExtremeHigh) {
                    currentExtremeHigh = c.high();
                    currentExtremeHighIdx = i;
                }
                double declineFromHigh = (currentExtremeHigh - c.low()) / currentExtremeHigh;
                if (declineFromHigh >= thresholdRatio) {
                    // Potwierdzony szczyt - dodajemy pivot i przełączamy kierunek
                    pivots.add(new Pivot(currentExtremeHighIdx, currentExtremeHigh, true));
                    trendUp = false;
                    currentExtremeLow = c.low();
                    currentExtremeLowIdx = i;
                }
            } else {
                // Trend spadkowy - analogicznie, śledzimy najniższy dołek
                if (c.low() < currentExtremeLow) {
                    currentExtremeLow = c.low();
                    currentExtremeLowIdx = i;
                }
                double riseFromLow = (c.high() - currentExtremeLow) / currentExtremeLow;
                if (riseFromLow >= thresholdRatio) {
                    pivots.add(new Pivot(currentExtremeLowIdx, currentExtremeLow, false));
                    trendUp = true;
                    currentExtremeHigh = c.high();
                    currentExtremeHighIdx = i;
                }
            }
        }

        // Analiza struktury: porównujemy ostatnie szczyty i dołki
        // Higher Highs = każdy kolejny szczyt wyżej niż poprzedni
        // Higher Lows = każdy kolejny dołek wyżej niż poprzedni
        List<Pivot> highs = pivots.stream().filter(Pivot::isHigh).toList();
        List<Pivot> lows = pivots.stream().filter(p -> !p.isHigh()).toList();

        boolean higherHighs = isAscending(highs);
        boolean higherLows = isAscending(lows);

        // Ostatni pivot - do zwrócenia w wyniku
        if (pivots.isEmpty()) {
            return new ZigZagResult(0, "UNKNOWN", false, false);
        }
        Pivot last = pivots.get(pivots.size() - 1);
        return new ZigZagResult(
                last.price(),
                last.isHigh() ? "HIGH" : "LOW",
                higherHighs,
                higherLows
        );
    }

    /**
     * Sprawdza czy ostatnie 2-3 pivoty tego samego typu są rosnące.
     * Dla wzrostowego trendu potrzebujemy minimum 2 higher highs.
     */
    private static boolean isAscending(List<Pivot> pivots) {
        if (pivots.size() < 2) return false;
        // Patrzymy na ostatnie 3 pivoty (lub 2 jeśli mamy tylko tyle)
        int start = Math.max(0, pivots.size() - 3);
        for (int i = start + 1; i < pivots.size(); i++) {
            if (pivots.get(i).price() <= pivots.get(i - 1).price()) {
                return false;
            }
        }
        return true;
    }
}
