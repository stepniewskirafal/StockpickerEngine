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
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementacja PriceDataSource pobierająca tygodniowe notowania ze stooq.pl.
 *
 * Logika HTTP + logowanie wydzielona do {@link StooqHttpSession} - ten serwis
 * skupia się tylko na parsowaniu CSV i mapowaniu na model domenowy.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StooqPriceDataSource implements PriceDataSource {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StooqHttpSession session;

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

        try {
            Thread.sleep(requestDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException("Przerwano podczas oczekiwania", e);
        }

        try {
            HttpResponse<String> response = session.fetchString(url, StandardCharsets.UTF_8);
            return handleResponse(response, tickerSymbol, weeksBack);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DataSourceException("Błąd pobierania danych dla " + tickerSymbol, e);
        }
    }

    private List<Candle> handleResponse(HttpResponse<String> response, String tickerSymbol,
                                         int weeksBack) throws DataSourceException {
        if (response.statusCode() != 200) {
            throw new DataSourceException(
                    "Stooq HTTP " + response.statusCode() + " dla " + tickerSymbol);
        }

        String body = response.body();

        if (body == null || body.length() < 50) {
            log.warn("Podejrzanie krótka odpowiedź dla {}: długość={}",
                    tickerSymbol, body == null ? 0 : body.length());
            throw new DataSourceException(
                    "Stooq zwrócił za krótką odpowiedź dla " + tickerSymbol);
        }

        String bodyLower = body.toLowerCase();

        if (session.isLoginEnabled()
                && (bodyLower.contains("name=\"a\"") || bodyLower.contains("formularz logowania"))) {
            session.markSessionExpired();
            throw new DataSourceException("Sesja stooq wygasła, ticker: " + tickerSymbol);
        }

        if (bodyLower.contains("brak danych")
                || bodyLower.contains("<!doctype html")
                || bodyLower.contains("<html")) {
            log.warn("Stooq nie ma danych dla tickera '{}'. Fragment odpowiedzi: {}",
                    tickerSymbol,
                    body.substring(0, Math.min(150, body.length())).replace("\n", " "));
            throw new DataSourceException(
                    "Ticker '" + tickerSymbol + "' nie istnieje na stooq.pl");
        }

        String firstLine = body.split("\n", 2)[0].trim();
        if (!firstLine.toLowerCase().contains("data") &&
            !firstLine.toLowerCase().contains("date")) {
            log.warn("Nieoczekiwany format odpowiedzi dla {}. Pierwsza linia: '{}'",
                    tickerSymbol, firstLine);
            throw new DataSourceException(
                    "Nieoczekiwany format odpowiedzi dla " + tickerSymbol);
        }

        List<Candle> allCandles = parseCsv(body, tickerSymbol);

        if (allCandles.size() > weeksBack) {
            return allCandles.subList(allCandles.size() - weeksBack, allCandles.size());
        }
        return allCandles;
    }

    private List<Candle> parseCsv(String csvContent, String tickerSymbol)
            throws DataSourceException {
        List<Candle> candles = new ArrayList<>();
        int skippedRecords = 0;

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new InputStreamReader(
                        new java.io.ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8))) {

            for (CSVRecord record : parser) {
                try {
                    String dateStr = getField(record, "Data", "Date");
                    String openStr = getField(record, "Otwarcie", "Open");
                    String highStr = getField(record, "Najwyzszy", "High");
                    String lowStr = getField(record, "Najnizszy", "Low");
                    String closeStr = getField(record, "Zamkniecie", "Close");
                    String volumeStr = getField(record, "Wolumen", "Volume");

                    if (dateStr == null || openStr == null || highStr == null
                            || lowStr == null || closeStr == null) {
                        skippedRecords++;
                        continue;
                    }

                    LocalDate date = LocalDate.parse(dateStr, DATE_FORMAT);
                    double open = Double.parseDouble(openStr);
                    double high = Double.parseDouble(highStr);
                    double low = Double.parseDouble(lowStr);
                    double close = Double.parseDouble(closeStr);
                    long volume = volumeStr == null || volumeStr.isBlank()
                            ? 0L : Long.parseLong(volumeStr);

                    candles.add(new Candle(date, open, high, low, close, volume));
                } catch (Exception e) {
                    skippedRecords++;
                    if (skippedRecords <= 3) {
                        log.debug("Pomijam uszkodzony rekord dla {}: {}",
                                tickerSymbol, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new DataSourceException("Błąd parsowania CSV dla " + tickerSymbol, e);
        }

        if (skippedRecords > 3) {
            log.warn("Dla {} pominięto łącznie {} uszkodzonych rekordów",
                    tickerSymbol, skippedRecords);
        }

        if (candles.isEmpty()) {
            throw new DataSourceException(
                    "Brak świec w odpowiedzi stooq dla " + tickerSymbol);
        }

        return candles;
    }

    private String getField(CSVRecord record, String... possibleNames) {
        for (String name : possibleNames) {
            if (record.isMapped(name)) {
                String value = record.get(name);
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
