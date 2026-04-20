package pl.rafal.stockpicker.service;

import org.junit.jupiter.api.Test;
import pl.rafal.stockpicker.model.Candle;
import pl.rafal.stockpicker.service.indicator.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Testy jednostkowe dla kalkulatorów wskaźników technicznych.
 *
 * Testy używają znanych serii wartości, dla których wyniki można zweryfikować
 * ręcznie lub w publikacjach. Dla RSI wartości referencyjne pochodzą z
 * oryginalnej pracy Wildera (1978), dla SMA - trywialne obliczenie ręczne.
 *
 * Ważne: używamy 'within' z AssertJ dla porównań double, bo równość dokładna
 * floatów po arytmetyce jest zawodna z powodu błędów zaokrąglania.
 */
class IndicatorCalculatorsTest {

    // =========================================================================
    // SMA
    // =========================================================================

    @Test
    void sma_shouldReturnCorrectAverageForFullPeriod() {
        // Seria 1,2,3,4,5 - średnia z 5 to 3.0 (trywialna weryfikacja)
        List<Double> prices = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        Double result = MovingAverageCalculator.sma(prices, 5);
        assertThat(result).isEqualTo(3.0);
    }

    @Test
    void sma_shouldReturnNullWhenNotEnoughData() {
        List<Double> prices = Arrays.asList(1.0, 2.0);
        // Żądamy SMA(5) ale mamy tylko 2 ceny - powinno być null, nie wyjątek
        assertThat(MovingAverageCalculator.sma(prices, 5)).isNull();
    }

    @Test
    void sma_shouldUseLastNElementsForTrailingAverage() {
        // Seria 10 elementów, SMA(3) powinno użyć tylko ostatnich 3: (8+9+10)/3 = 9.0
        List<Double> prices = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        Double result = MovingAverageCalculator.sma(prices, 3);
        assertThat(result).isEqualTo(9.0);
    }

    // =========================================================================
    // RSI - test z danymi referencyjnymi z Wildera
    // =========================================================================

    @Test
    void rsi_shouldMatchWilderReferenceValues() {
        // Klasyczna seria testowa z książki Wildera "New Concepts in Technical
        // Trading Systems" (1978). Dla tej serii 14 cen zamknięcia poprzedzanych
        // ceną bazową, pierwsza policzalna wartość RSI powinna wynosić ~70.53.
        List<Double> closes = Arrays.asList(
                44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42,
                45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28
        );
        Double rsi = RsiCalculator.rsi(closes, 14);
        assertThat(rsi).isNotNull();
        // Wartość z Wildera dla tej serii to ~70.53 +/- zaokrąglenie
        assertThat(rsi).isCloseTo(70.53, within(1.0));
    }

    @Test
    void rsi_shouldReturn100ForUninterruptedUptrend() {
        // Same wzrosty = zero strat = RSI = 100 (edge case)
        List<Double> closes = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            closes.add((double) i); // 1,2,3...20
        }
        Double rsi = RsiCalculator.rsi(closes, 14);
        assertThat(rsi).isEqualTo(100.0);
    }

    @Test
    void rsi_shouldReturnNullForInsufficientData() {
        List<Double> closes = Arrays.asList(1.0, 2.0, 3.0);
        assertThat(RsiCalculator.rsi(closes, 14)).isNull();
    }

    // =========================================================================
    // MACD
    // =========================================================================

    @Test
    void macd_shouldReturnNullForShortSeries() {
        // MACD potrzebuje min 26+9=35 punktów
        List<Double> prices = Arrays.asList(1.0, 2.0, 3.0);
        assertThat(MacdCalculator.macd(prices)).isNull();
    }

    @Test
    void macd_shouldComputeAllThreeComponents() {
        // Generujemy 60 punktów rosnącego trendu - spodziewamy się niezerowych wartości
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            prices.add(100.0 + i * 0.5); // powolny wzrost
        }
        var result = MacdCalculator.macd(prices);
        assertThat(result).isNotNull();
        assertThat(result.macdLine()).isNotNull().isPositive();
        assertThat(result.signalLine()).isNotNull().isPositive();
        assertThat(result.histogram()).isNotNull();
    }

    // =========================================================================
    // ZigZag
    // =========================================================================

    @Test
    void zigzag_shouldDetectHigherHighsInUptrend() {
        // Generujemy sekwencję która tworzy 2 higher highs i 2 higher lows
        List<Candle> candles = buildUptrendingCandles();
        var result = ZigZagCalculator.zigzag(candles, 5.0);
        assertThat(result).isNotNull();
        // W jednoznacznym uptrendzie oczekujemy byczej struktury
        assertThat(result.higherHighs()).isTrue();
    }

    @Test
    void zigzag_shouldHandleTooShortSeries() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles.add(new Candle(LocalDate.now().plusDays(i), 100, 101, 99, 100, 1000));
        }
        // Za krótka seria - powinno zwrócić neutralny wynik, nie wyjątek
        var result = ZigZagCalculator.zigzag(candles, 5.0);
        assertThat(result).isNotNull();
        assertThat(result.isBullish()).isFalse();
    }

    /**
     * Pomocnicza metoda budująca sekwencję świec tworzących widoczny uptrend
     * z wyraźnymi swing highs i lows przekraczającymi próg 5%.
     */
    private List<Candle> buildUptrendingCandles() {
        List<Candle> candles = new ArrayList<>();
        LocalDate start = LocalDate.now().minusWeeks(50);

        // Wzorzec: 10 tygodni wzrostu, 5 korekty, 10 wzrostu, 5 korekty, 10 wzrostu
        // - daje 3 wyższe szczyty i 2 wyższe dołki
        double price = 100.0;
        double[] pattern = {
                // 10 tyg wzrostu z 100 do 120
                102, 104, 106, 108, 110, 112, 114, 116, 118, 120,
                // 5 tyg korekty do 112 (-6.7%)
                118, 116, 114, 113, 112,
                // 10 tyg wzrostu z 112 do 135 (nowy szczyt wyższy od 120)
                114, 117, 120, 123, 126, 128, 130, 132, 134, 135,
                // 5 tyg korekty do 125 (-7.4%, wyższy dołek niż 112)
                132, 130, 128, 126, 125,
                // 10 tyg wzrostu z 125 do 150 (jeszcze wyższy szczyt)
                128, 131, 134, 137, 140, 142, 144, 146, 148, 150
        };

        for (int i = 0; i < pattern.length; i++) {
            double close = pattern[i];
            double high = close * 1.01;
            double low = close * 0.99;
            double open = i == 0 ? 100 : pattern[i - 1];
            candles.add(new Candle(start.plusWeeks(i), open, high, low, close, 10000));
        }
        return candles;
    }
}
