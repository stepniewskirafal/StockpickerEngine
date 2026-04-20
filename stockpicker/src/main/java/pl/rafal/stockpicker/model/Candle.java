package pl.rafal.stockpicker.model;

import java.time.LocalDate;

/**
 * Reprezentuje pojedynczą świecę tygodniową (OHLCV) dla instrumentu finansowego.
 *
 * Używam Java record zamiast klasy POJO z setterami, bo świeca to obiekt
 * immutable z natury rzeczy - raz zamknięte notowanie jest zamknięte na zawsze.
 * Rekord automatycznie generuje konstruktor, gettery, equals/hashCode i toString.
 *
 * OHLCV to standardowy akronim giełdowy:
 *   O = Open (otwarcie)
 *   H = High (maksimum)
 *   L = Low (minimum)
 *   C = Close (zamknięcie) - to najważniejsza wartość dla większości wskaźników
 *   V = Volume (wolumen obrotu)
 *
 * W obliczeniach SMA, RSI i MACD używamy wyłącznie ceny zamknięcia (close),
 * bo to ona zawiera "finalne zdanie rynku" z danego okresu. Otwarcie i maksimum
 * potrzebne są do analizy formacji świecowych, a wolumen do potwierdzania sygnałów.
 */
public record Candle(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    /**
     * Walidacja - nie powinno być ujemnych cen ani daty null.
     * Rekord pozwala na kompaktowy konstruktor walidujący.
     */
    public Candle {
        if (date == null) {
            throw new IllegalArgumentException("Data świecy nie może być null");
        }
        if (open < 0 || high < 0 || low < 0 || close < 0) {
            throw new IllegalArgumentException("Ceny nie mogą być ujemne");
        }
        if (high < low) {
            throw new IllegalArgumentException("High nie może być mniejsze niż low");
        }
    }
}
