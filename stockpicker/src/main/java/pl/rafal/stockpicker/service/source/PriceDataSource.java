package pl.rafal.stockpicker.service.source;

import pl.rafal.stockpicker.model.Candle;
import java.util.List;

/**
 * Kontrakt dla dowolnego źródła danych cenowych.
 *
 * Dlaczego interfejs, a nie konkretna klasa?
 * Bo w świecie realnym źródła danych finansowych mają tendencję do awarii,
 * zmian cen i zmian formatu. Abstrakcja pozwala nam:
 *
 *   1. Podmienić implementację bez zmiany logiki biznesowej
 *      (np. dziś stooq, jutro Yahoo Finance jako fallback)
 *
 *   2. Mockować źródło w testach jednostkowych
 *      (bez interfejsu nie możemy testować bez internetu)
 *
 *   3. Chainować źródła w strategii "pierwsze co działa"
 *      (ChainedPriceDataSource spróbuje najpierw stooq, potem Yahoo)
 *
 * Ta filozofia to wariant wzorca Strategy w połączeniu z Dependency Inversion -
 * wysokopoziomowa logika (silnik screeningu) zależy od abstrakcji, nie od
 * konkretnej implementacji.
 */
public interface PriceDataSource {

    /**
     * Pobiera historyczne świece tygodniowe dla danego instrumentu.
     *
     * @param tickerSymbol symbol w formacie oczekiwanym przez źródło
     *                     (np. "xtb" dla stooq)
     * @param weeksBack ile tygodni wstecz chcemy pobrać (minimum 210 dla SMA(200))
     * @return lista świec posortowanych chronologicznie (najstarsza pierwsza)
     * @throws DataSourceException gdy dane nie mogą być pobrane
     */
    List<Candle> fetchWeeklyCandles(String tickerSymbol, int weeksBack) throws DataSourceException;

    /**
     * Specjalistyczny wyjątek pozwalający odróżnić błędy pobierania danych
     * od innych rzeczy które mogą pójść źle. Dzięki temu w warstwie serwisów
     * możemy świadomie obsłużyć sytuację "źródło nie działa" - np. przez
     * pominięcie spółki lub użycie cache'u.
     */
    class DataSourceException extends Exception {
        public DataSourceException(String message) {
            super(message);
        }

        public DataSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
