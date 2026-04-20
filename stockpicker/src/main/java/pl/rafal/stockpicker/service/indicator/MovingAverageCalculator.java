package pl.rafal.stockpicker.service.indicator;

import java.util.List;

/**
 * Kalkulator średnich kroczących - SMA (Simple Moving Average) i
 * SMMA (Smoothed Moving Average, czasem nazywana RMA lub Wilder's MA).
 *
 * Te dwa wskaźniki są matematycznie podobne (obie wygładzają cenę w czasie),
 * ale mają zupełnie różne zastosowania. SMA daje "migawkę" średniej z
 * ustalonego okna i reaguje skokowo kiedy stary punkt wypada z okna. SMMA
 * używa całej historii z eksponencjalnie malejącą wagą, przez co jest
 * gładsza i wolniejsza w reakcji.
 *
 * W naszym silniku:
 * - SMA używamy do określenia trendu i dystansów cena-średnia
 * - SMMA używamy jako filtra potwierdzającego, bo mniej falszywie reaguje
 */
public final class MovingAverageCalculator {

    private MovingAverageCalculator() {
        // klasa statyczna - nie ma instancjowania
    }

    /**
     * Oblicza SMA z ostatnich N wartości w serii.
     *
     * Wzór: SMA = (P1 + P2 + ... + Pn) / n
     *
     * Zwraca null jeśli brakuje danych - to nie jest błąd, tylko informacja
     * że wskaźnik nie może być policzony przy danej historii.
     *
     * @param prices lista cen (zwykle close'y), uporządkowana chronologicznie
     * @param period długość okna SMA (np. 10, 50, 100, 200)
     * @return wartość SMA na ostatniej pozycji, lub null jeśli za mało danych
     */
    public static Double sma(List<Double> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            return null;
        }
        // Używamy klasycznej pętli zamiast streamów - dla małych N streams są
        // wolniejsze od zwykłej pętli, a tu N bywa rzędu 10-200.
        double sum = 0.0;
        int startIndex = prices.size() - period;
        for (int i = startIndex; i < prices.size(); i++) {
            sum += prices.get(i);
        }
        return sum / period;
    }

    /**
     * Oblicza SMMA (Smoothed Moving Average).
     *
     * Wzór rekurencyjny Wildera:
     *   SMMA[1] = SMA[N]  -- inicjalizacja pierwszego punktu zwykłą średnią
     *   SMMA[i] = (SMMA[i-1] * (N-1) + P[i]) / N
     *
     * Każdy kolejny punkt to 1/N ceny bieżącej + (N-1)/N poprzedniej SMMA.
     * To daje eksponencjalny spadek wag dla starszych danych, ale nie wykładniczy
     * jak EMA (która ma alpha = 2/(N+1) zamiast 1/N).
     *
     * SMMA jest mniej czuła od EMA przy tym samym N, więc daje mniej fałszywek.
     *
     * @param prices lista cen chronologicznie
     * @param period długość okna
     * @return wartość SMMA na ostatniej pozycji, lub null jeśli za mało danych
     */
    public static Double smma(List<Double> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            return null;
        }
        // Inicjalizacja: pierwsze SMMA to SMA z pierwszych N wartości
        double smma = 0.0;
        for (int i = 0; i < period; i++) {
            smma += prices.get(i);
        }
        smma /= period;

        // Rekurencyjnie aplikujemy formułę Wildera dla pozostałych punktów
        for (int i = period; i < prices.size(); i++) {
            smma = (smma * (period - 1) + prices.get(i)) / period;
        }
        return smma;
    }

    /**
     * Oblicza EMA (Exponential MA) - przydatne jako krok pośredni w MACD.
     *
     * Wzór: EMA[i] = alpha * P[i] + (1 - alpha) * EMA[i-1]
     *   gdzie alpha = 2 / (N + 1)
     *
     * EMA jest szybsza w reakcji niż SMMA przy tym samym N, bo dla N=12
     * alpha = 0.154 wobec 1/12 = 0.083 dla SMMA.
     *
     * @param prices lista cen
     * @param period długość EMA
     * @return ostatnia wartość EMA, lub null jeśli za mało danych
     */
    public static Double ema(List<Double> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            return null;
        }
        double alpha = 2.0 / (period + 1);

        // Start: SMA z pierwszych N wartości jako zarodek
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += prices.get(i);
        }
        ema /= period;

        for (int i = period; i < prices.size(); i++) {
            ema = alpha * prices.get(i) + (1 - alpha) * ema;
        }
        return ema;
    }

    /**
     * Zwraca całą serię EMA (nie tylko ostatni punkt) - to potrzebne dla MACD,
     * który wymaga historii EMA żeby policzyć linię sygnału z EMA(9) na MACD.
     */
    public static List<Double> emaSeries(List<Double> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            return List.of();
        }
        double alpha = 2.0 / (period + 1);
        double[] result = new double[prices.size()];

        // Pierwsze (period-1) wartości nie mają EMA - wypełniamy NaN, a potem
        // filtrujemy je przed zwróceniem
        for (int i = 0; i < period - 1; i++) {
            result[i] = Double.NaN;
        }

        // Zarodek to SMA z pierwszego okna
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += prices.get(i);
        }
        ema /= period;
        result[period - 1] = ema;

        for (int i = period; i < prices.size(); i++) {
            ema = alpha * prices.get(i) + (1 - alpha) * ema;
            result[i] = ema;
        }

        // Zwracamy całą serię - wywołujący musi pamiętać że pierwsze (period-1)
        // elementów to NaN. Ale to celowe, bo wyrównuje indeksowanie z prices.
        return java.util.Arrays.stream(result).boxed().toList();
    }
}
