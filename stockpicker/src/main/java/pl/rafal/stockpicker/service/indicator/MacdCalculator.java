package pl.rafal.stockpicker.service.indicator;

import pl.rafal.stockpicker.model.IndicatorSnapshot.MacdResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Kalkulator MACD (Moving Average Convergence Divergence).
 *
 * MACD to wskaźnik trendu wymyślony przez Gerald'a Appel'a w latach 70.
 * Mierzy różnicę między krótką i długą EMA, pokazując przyspieszenie lub
 * spowolnienie momentum cenowego.
 *
 * Składa się z trzech komponentów:
 *
 * 1. Linia MACD = EMA(12) - EMA(26)
 *    Gdy krótsza EMA jest powyżej dłuższej - trend wzrostowy.
 *    Gdy krótsza jest poniżej dłuższej - trend spadkowy.
 *
 * 2. Linia sygnału = EMA(9) z Linii MACD
 *    To "wygładzona" wersja MACD, wolniejsza w reakcji.
 *    Cross Linii MACD nad Linią sygnału = sygnał kupna.
 *    Cross Linii MACD pod Linią sygnału = sygnał sprzedaży.
 *
 * 3. Histogram = Linia MACD - Linia sygnału
 *    Pokazuje przyspieszenie/spowolnienie momentum.
 *    Rosnący dodatni histogram = rosnące momentum byków.
 *    Malejący histogram (nawet dodatni) = słabnący impet, ostrzeżenie.
 *
 * Parametry (12, 26, 9) to standard Appel'a. Niektórzy używają (5, 35, 5)
 * do szybszych sygnałów albo (19, 39, 9) do wolniejszych - my trzymamy
 * się klasyki, bo jest to konfiguracja używana w TradingView domyślnie.
 */
public final class MacdCalculator {

    // Standardowa konfiguracja MACD - mogłaby być parametryzowana ale
    // w praktyce nikt tego nie zmienia, więc hardcoduję jako stałe
    public static final int FAST_PERIOD = 12;
    public static final int SLOW_PERIOD = 26;
    public static final int SIGNAL_PERIOD = 9;

    private MacdCalculator() {}

    /**
     * Oblicza MACD dla ostatniego punktu w serii cen.
     *
     * Implementacja idzie w trzech krokach:
     *
     * KROK 1: Obliczamy serie EMA(12) i EMA(26) z cen.
     * KROK 2: Z nich wyliczamy serię MACD = EMA12 - EMA26.
     * KROK 3: Z serii MACD wyliczamy EMA(9) jako linię sygnału.
     *
     * Potrzebujemy pełnych serii EMA a nie tylko ostatnich punktów,
     * bo linia sygnału to EMA ze wszystkich historycznych wartości MACD.
     *
     * @param closes lista cen zamknięcia chronologicznie
     * @return MacdResult z linią MACD, sygnałem i histogramem, lub null
     */
    public static MacdResult macd(List<Double> closes) {
        // Do obliczenia EMA(26) potrzeba minimum 26 punktów, do dodania EMA(9)
        // na serii MACD potrzeba dodatkowo 9 punktów serii MACD, czyli
        // 26 + 9 - 1 = 34 punktów cen jako absolutne minimum. Daję 35 dla bezpieczeństwa.
        if (closes == null || closes.size() < SLOW_PERIOD + SIGNAL_PERIOD) {
            return null;
        }

        // KROK 1: Obie EMA w postaci serii
        List<Double> emaFast = MovingAverageCalculator.emaSeries(closes, FAST_PERIOD);
        List<Double> emaSlow = MovingAverageCalculator.emaSeries(closes, SLOW_PERIOD);

        // KROK 2: Seria MACD jako różnica.
        // Uwaga: pierwsze elementy obu serii to NaN (do wyrównania indeksów),
        // więc musimy je pomijać przy liczeniu różnic.
        List<Double> macdSeries = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            double fast = emaFast.get(i);
            double slow = emaSlow.get(i);
            if (Double.isNaN(fast) || Double.isNaN(slow)) {
                // Dopóki nie mamy obu EMA, MACD nie istnieje
                macdSeries.add(Double.NaN);
            } else {
                macdSeries.add(fast - slow);
            }
        }

        // Wyfiltruj NaN-y przed liczeniem linii sygnału - bierzemy tylko
        // punkty gdzie MACD jest już liczalne
        List<Double> validMacd = macdSeries.stream()
                .filter(v -> !Double.isNaN(v))
                .toList();

        // KROK 3: Linia sygnału = EMA(9) z valid MACD
        Double signalLine = MovingAverageCalculator.ema(validMacd, SIGNAL_PERIOD);
        if (signalLine == null) {
            return null;
        }

        double macdLine = validMacd.get(validMacd.size() - 1);
        double histogram = macdLine - signalLine;

        return new MacdResult(macdLine, signalLine, histogram);
    }
}
