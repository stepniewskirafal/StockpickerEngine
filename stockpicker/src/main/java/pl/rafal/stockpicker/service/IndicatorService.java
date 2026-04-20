package pl.rafal.stockpicker.service;

import org.springframework.stereotype.Service;
import pl.rafal.stockpicker.model.Candle;
import pl.rafal.stockpicker.model.IndicatorSnapshot;
import pl.rafal.stockpicker.service.indicator.MacdCalculator;
import pl.rafal.stockpicker.service.indicator.MovingAverageCalculator;
import pl.rafal.stockpicker.service.indicator.RsiCalculator;
import pl.rafal.stockpicker.service.indicator.ZigZagCalculator;

import java.util.List;

/**
 * Orkiestrator obliczeń wskaźników technicznych.
 *
 * Ta klasa sama nie zawiera logiki matematycznej - deleguje ją do
 * wyspecjalizowanych kalkulatorów w pakiecie indicator. Jej zadaniem jest
 * jedynie wiedzieć w jakiej kolejności uruchomić kalkulatory i zebrać
 * wyniki w jeden IndicatorSnapshot.
 *
 * Dlaczego tak? Bo każdy kalkulator ma inną odpowiedzialność i może być
 * testowany w izolacji. Gdyby logika była w jednej wielkiej klasie,
 * test jednostkowy na przykład RSI wymagałby setupowania całego kontekstu,
 * co jest niewygodne. Zasada pojedynczej odpowiedzialności (SRP z SOLID)
 * mówi że każda klasa powinna mieć jeden powód do zmiany.
 *
 * Klasa jest @Service żeby Spring zarządzał jej cyklem życia i mógł ją
 * wstrzyknąć jako zależność do innych komponentów.
 */
@Service
public class IndicatorService {

    /**
     * Parametry obliczeń - w produkcji można by to wyciągnąć do
     * application.properties, ale dla prostoty zostawiam jako stałe.
     * Wartości są standardem używanym przez większość platform tradingowych.
     */
    private static final int SMA_10 = 10;
    private static final int SMA_50 = 50;
    private static final int SMA_100 = 100;
    private static final int SMA_200 = 200;
    private static final int SMMA_PERIOD = 20;
    private static final int RSI_PERIOD = 14;
    private static final double ZIGZAG_THRESHOLD_PERCENT = 5.0;

    /**
     * Oblicza pełny snapshot wskaźników dla danej listy świec.
     *
     * Metoda jest idempotentna - dla tej samej listy świec zawsze zwróci
     * ten sam wynik. Nie ma efektów ubocznych, nie zapisuje nic do bazy.
     * To czyni ją łatwą do testowania i cache'owania na wyższych poziomach.
     *
     * @param candles lista świec tygodniowych chronologicznie (najstarsza pierwsza)
     * @return pełny snapshot wszystkich wskaźników, z null-ami dla wskaźników
     *         których nie da się policzyć z powodu za krótkiej historii
     */
    public IndicatorSnapshot calculateSnapshot(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nie można obliczyć wskaźników dla pustej listy świec");
        }

        // Ekstraktujemy serię cen zamknięcia - większość wskaźników używa
        // tylko close. Wyjątkiem jest ZigZag który potrzebuje high i low.
        List<Double> closes = candles.stream()
                .map(Candle::close)
                .toList();

        // Cena bieżąca to zamknięcie najnowszej świecy w serii
        double currentPrice = candles.get(candles.size() - 1).close();

        // Obliczamy wszystkie SMA - cztery różne horyzonty czasowe.
        // Każdy może zwrócić null jeśli historia jest za krótka.
        Double sma10  = MovingAverageCalculator.sma(closes, SMA_10);
        Double sma50  = MovingAverageCalculator.sma(closes, SMA_50);
        Double sma100 = MovingAverageCalculator.sma(closes, SMA_100);
        Double sma200 = MovingAverageCalculator.sma(closes, SMA_200);

        // SMMA(20) - średnia wygładzona do potwierdzania trendu
        Double smma20 = MovingAverageCalculator.smma(closes, SMMA_PERIOD);

        // RSI(14) - wskaźnik siły relatywnej
        Double rsi = RsiCalculator.rsi(closes, RSI_PERIOD);

        // MACD z konfiguracją (12, 26, 9) - wyniki w formie rekordu z trzema wartościami
        var macd = MacdCalculator.macd(closes);

        // ZigZag z progiem 5% - pracuje na pełnych świecach (high/low)
        var zigzag = ZigZagCalculator.zigzag(candles, ZIGZAG_THRESHOLD_PERCENT);

        return new IndicatorSnapshot(
                currentPrice, sma10, sma50, sma100, sma200,
                smma20, rsi, macd, zigzag
        );
    }
}
