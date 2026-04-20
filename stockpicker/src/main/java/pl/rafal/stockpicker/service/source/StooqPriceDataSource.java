package pl.rafal.stockpicker.service.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pl.rafal.stockpicker.model.Candle;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pobiera tygodniowe notowania ze stooq.pl/q/d/l/?s=TICKER&i=w. To endpoint
 * publiczny (bez consent wall, bez logowania), dlatego korzysta tylko ze
 * zwykłego {@link StooqHttpClient}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StooqPriceDataSource implements PriceDataSource {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MIN_RESPONSE_LENGTH = 50;

    private final StooqHttpClient http;

    @Value("${stockpicker.stooq.base-url:https://stooq.pl/q/d/l/}")
    private String baseUrl;

    @Value("${stockpicker.stooq.request-delay-ms:200}")
    private long requestDelayMs;

    @Override
    @Cacheable(value = "weeklyCandles", key = "#tickerSymbol + '-' + #weeksBack")
    public List<Candle> fetchWeeklyCandles(String tickerSymbol, int weeksBack)
            throws DataSourceException {
        String url = baseUrl + "?s=" + tickerSymbol.toLowerCase() + "&i=w";
        log.info("Pobieram dane dla {} z {}", tickerSymbol, url);

        throttle();
        String body = fetchBody(url, tickerSymbol);
        validateCsvBody(body, tickerSymbol);

        List<Candle> all = parseCsv(body, tickerSymbol);
        return all.size() > weeksBack ? all.subList(all.size() - weeksBack, all.size()) : all;
    }

    private void throttle() throws DataSourceException {
        try {
            Thread.sleep(requestDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException("Przerwano podczas oczekiwania", e);
        }
    }

    private String fetchBody(String url, String ticker) throws DataSourceException {
        try {
            return http.getBody(url);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new DataSourceException("Błąd pobierania danych dla " + ticker, e);
        }
    }

    private void validateCsvBody(String body, String ticker) throws DataSourceException {
        if (body == null || body.length() < MIN_RESPONSE_LENGTH) {
            throw new DataSourceException("Stooq zwrócił za krótką odpowiedź dla " + ticker);
        }
        String bodyLower = body.toLowerCase();
        if (bodyLower.contains("brak danych") || bodyLower.contains("<html")) {
            log.warn("Stooq nie ma danych dla tickera '{}'. Fragment: {}",
                    ticker, body.substring(0, Math.min(150, body.length())).replace("\n", " "));
            throw new DataSourceException("Ticker '" + ticker + "' nie istnieje na stooq.pl");
        }
        String firstLine = body.split("\n", 2)[0].trim().toLowerCase();
        if (!firstLine.contains("data") && !firstLine.contains("date")) {
            throw new DataSourceException(
                    "Nieoczekiwany format CSV dla " + ticker + ", pierwsza linia: '" + firstLine + "'");
        }
    }

    private List<Candle> parseCsv(String csv, String ticker) throws DataSourceException {
        List<Candle> candles = new ArrayList<>();
        int skipped = 0;
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(csv))) {
            for (CSVRecord record : parser) {
                Candle candle = parseRecord(record);
                if (candle != null) candles.add(candle); else skipped++;
            }
        } catch (IOException e) {
            throw new DataSourceException("Błąd parsowania CSV dla " + ticker, e);
        }
        if (skipped > 3) log.warn("Dla {} pominięto {} uszkodzonych rekordów", ticker, skipped);
        if (candles.isEmpty()) throw new DataSourceException("Brak świec w odpowiedzi stooq dla " + ticker);
        return candles;
    }

    private Candle parseRecord(CSVRecord record) {
        try {
            String dateStr = field(record, "Data", "Date");
            String openStr = field(record, "Otwarcie", "Open");
            String highStr = field(record, "Najwyzszy", "High");
            String lowStr = field(record, "Najnizszy", "Low");
            String closeStr = field(record, "Zamkniecie", "Close");
            String volumeStr = field(record, "Wolumen", "Volume");
            if (dateStr == null || openStr == null || highStr == null || lowStr == null || closeStr == null) {
                return null;
            }
            return new Candle(
                    LocalDate.parse(dateStr, DATE_FORMAT),
                    Double.parseDouble(openStr),
                    Double.parseDouble(highStr),
                    Double.parseDouble(lowStr),
                    Double.parseDouble(closeStr),
                    volumeStr == null || volumeStr.isBlank() ? 0L : Long.parseLong(volumeStr));
        } catch (Exception e) {
            return null;
        }
    }

    private String field(CSVRecord record, String... names) {
        for (String name : names) {
            if (record.isMapped(name)) {
                String value = record.get(name);
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
