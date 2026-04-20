package pl.rafal.stockpicker.service.indicator;

import java.util.List;

/**
 * Kalkulator RSI (Relative Strength Index) według oryginalnego wzoru
 * J. Wellesa Wildera z 1978 roku.
 *
 * WAŻNE: Istnieją dwie wersje RSI w obiegu. "Uproszczona" używa zwykłej SMA
 * do uśredniania zysków i strat - to wersja podawana w wielu tutorialach,
 * ale jest BŁĘDNA w tym sensie, że nie zgadza się z TradingView, stooq,
 * xStation i innymi profesjonalnymi platformami.
 *
 * Prawidłowa wersja używa SMMA (Smoothed Moving Average - Wilder's smoothing),
 * która jest implementowana formułą rekurencyjną:
 *   avg[i] = (avg[i-1] * (N-1) + value[i]) / N
 *
 * Ta klasa implementuje prawidłową wersję, więc wyniki będą zgodne
 * z profesjonalnymi narzędziami.
 *
 * Interpretacja wartości RSI:
 *   RSI > 70  -> strefa wykupienia (overbought), ostrzeżenie przed korektą
 *   RSI < 30  -> strefa wyprzedania (oversold), potencjalna okazja kupna
 *   50        -> poziom neutralny, linia równowagi byków i niedźwiedzi
 *   50-70     -> strefa siły trendu wzrostowego bez wykupienia (IDEALNA)
 */
public final class RsiCalculator {

    private RsiCalculator() {}

    /**
     * Oblicza RSI dla ostatniego punktu w serii cen zamknięcia.
     *
     * Algorytm Wildera składa się z trzech kroków:
     *
     * KROK 1: Obliczenie zmian cen dzień po dniu
     *   change[i] = price[i] - price[i-1]
     *   gain[i] = max(change, 0)
     *   loss[i] = max(-change, 0)
     *
     * KROK 2: Uśrednienie zysków i strat techniką Wildera (SMMA)
     *   avgGain[period] = SMA(gain, period)         -- inicjalizacja
     *   avgGain[i>period] = (avgGain[i-1]*(period-1) + gain[i]) / period
     *   analogicznie dla avgLoss
     *
     * KROK 3: Obliczenie RSI
     *   RS = avgGain / avgLoss
     *   RSI = 100 - (100 / (1 + RS))
     *
     * Standardowy okres to 14 (Wilder używał 14 dni, ale na interwale W1
     * będzie to 14 tygodni).
     *
     * @param closes lista cen zamknięcia chronologicznie
     * @param period długość okna RSI, standardowo 14
     * @return RSI z zakresu 0-100, lub null jeśli za mało danych
     */
    public static Double rsi(List<Double> closes, int period) {
        // Potrzebujemy minimum period+1 punktów bo pierwsza zmiana wymaga
        // dwóch pierwszych cen
        if (closes == null || closes.size() < period + 1 || period <= 0) {
            return null;
        }

        // KROK 1: zmiany cena-cena-1
        int n = closes.size();
        double[] gains = new double[n];
        double[] losses = new double[n];
        for (int i = 1; i < n; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            // Rozdzielamy zyski i straty na dwie osobne serie dla uproszczenia
            // dalszej formuły
            gains[i] = Math.max(change, 0);
            losses[i] = Math.max(-change, 0);
        }

        // KROK 2: inicjalizacja avg za pomocą SMA z pierwszych 'period' zmian
        // Zmiany są w indeksach 1..period (indeks 0 nie ma zmiany)
        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 1; i <= period; i++) {
            avgGain += gains[i];
            avgLoss += losses[i];
        }
        avgGain /= period;
        avgLoss /= period;

        // Dalsze punkty: formuła Wildera SMMA
        for (int i = period + 1; i < n; i++) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period;
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period;
        }

        // KROK 3: RSI z ostatnich uśrednionych wartości
        // Edge case: brak strat oznacza RSI = 100 (czysta strona popytu)
        if (avgLoss == 0) {
            return 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
