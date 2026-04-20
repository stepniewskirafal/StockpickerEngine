package pl.rafal.stockpicker.service.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import pl.rafal.stockpicker.model.QuoteRow;

import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Serwis pobierający publiczne notowania WIG20 ze stooq.pl.
 *
 * STRATEGIA: CSV PRIMARY + HTML FALLBACK
 * ======================================
 * Stooq pokazuje anonimowym użytkownikom consent wall (CMP) blokujący
 * parsowanie strony HTML. Rozwiązujemy to poprzez współdzieloną zalogowaną
 * sesję ({@link StooqHttpSession}) - logowanie omija consent wall.
 *
 * Preferujemy endpoint CSV /q/l/ bo jest stabilniejszy do parsowania
 * niż HTML (który zmienia layout). Jeśli CSV zwróci zbyt mało wierszy
 * (może zwracać tylko sam indeks zamiast 20 składników), spadamy do HTML.
 *
 * CEL DIAGNOSTYCZNY
 * =================
 * Ta zakładka służy do weryfikacji, że aplikacja ma połączenie ze stooq
 * i że logowanie działa. Jeśli widzimy dane - infrastruktura jest OK.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StooqPublicPageService {

    private final StooqHttpSession session;
    private final PlaywrightStooqSession playwrightSession;

    @Value("${stockpicker.stooq.wig20-url:https://stooq.pl/q/i/?s=wig20}")
    private String wig20HtmlUrl;

    @Value("${stockpicker.stooq.wig20-csv-url:https://stooq.pl/q/l/?s=wig20&f=sd2t2ohlcv&h&e=csv}")
    private String wig20CsvUrl;

    /**
     * Minimalna liczba wierszy w odpowiedzi CSV, żeby uznać ją za pełny
     * komplet składników indeksu. Jeśli CSV zwraca mniej (np. sam indeks
     * jako 1 wiersz), przełączamy się na scraping HTML.
     */
    private static final int MIN_ROWS_FOR_CSV_SUCCESS = 10;

    @Cacheable(value = "wig20Page", key = "'wig20'")
    public ScrapeResult fetchWig20() {
        long startTime = System.currentTimeMillis();

        ScrapeResult csvResult = tryFetchCsv(startTime);
        if (csvResult.successful && csvResult.quotes.size() >= MIN_ROWS_FOR_CSV_SUCCESS) {
            return csvResult;
        }

        log.info("CSV dał {} wierszy - fallback do HTML",
                csvResult.successful ? csvResult.quotes.size() : 0);

        return tryFetchHtml(startTime);
    }

    /**
     * Próbuje pobrać dane z endpointu CSV /q/l/. Zwraca ScrapeResult z listą
     * QuoteRow albo failure, jeśli cokolwiek się nie udało. Nie rzuca wyjątków
     * - pozwala wywołującemu zdecydować czy spróbować fallback.
     */
    private ScrapeResult tryFetchCsv(long startTime) {
        log.info("Pobieram CSV WIG20 z {}", wig20CsvUrl);
        try {
            HttpResponse<String> response = session.fetchString(wig20CsvUrl, StandardCharsets.UTF_8);
            long elapsed = System.currentTimeMillis() - startTime;
            String contentType = response.headers().firstValue("content-type").orElse("?");
            int bodyLen = response.body() == null ? 0 : response.body().length();
            log.info("CSV response: HTTP {} ({} bajtów, content-type={}, {} ms)",
                    response.statusCode(), bodyLen, contentType, elapsed);

            if (response.statusCode() != 200) {
                String snippet = response.body() == null ? null
                        : response.body().substring(0, Math.min(500, response.body().length()));
                return ScrapeResult.failure(
                        "CSV HTTP " + response.statusCode(), wig20CsvUrl, elapsed, snippet);
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return ScrapeResult.failure("CSV pusty", wig20CsvUrl, elapsed, null);
            }

            // Stooq dla niezalogowanych zwraca HTML z apikey zamiast CSV.
            String bodyLower = body.toLowerCase();
            if (bodyLower.contains("<html") || bodyLower.contains("apikey")
                    || bodyLower.contains("uzyskaj")) {
                log.warn("Endpoint CSV zwrócił HTML/apikey - sesja niezalogowana? Snippet: {}",
                        body.substring(0, Math.min(200, body.length())).replace("\n", " "));
                return ScrapeResult.failure(
                        "CSV endpoint zwrócił HTML zamiast danych (apikey wall)",
                        wig20CsvUrl, elapsed, body.substring(0, Math.min(500, body.length())));
            }

            List<QuoteRow> quotes = parseCsv(body);
            log.info("CSV sparsowany: {} wierszy w {} ms", quotes.size(), elapsed);
            if (quotes.isEmpty()) {
                log.warn("CSV miał 0 wierszy - pierwsza linia: '{}'",
                        body.split("\n", 2)[0]);
            }
            return ScrapeResult.success(quotes, wig20CsvUrl, elapsed);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("Błąd pobierania CSV ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return ScrapeResult.failure(
                    "CSV error: " + e.getClass().getSimpleName() + " - " + e.getMessage(),
                    wig20CsvUrl, elapsed, null);
        }
    }

    /**
     * Fallback: pobiera stronę HTML przez Playwright (headless Chromium).
     * Stooq.pl WIG20 jest zza Google Funding Choices CMP, który dostarcza
     * tabelę z komponentami dopiero po wykonaniu JavaScriptu i akceptacji
     * zgody. Zwykły HttpClient widzi tylko 200KB JS-loadera.
     */
    private ScrapeResult tryFetchHtml(long startTime) {
        log.info("Pobieram HTML WIG20 z {} (przez Playwright)", wig20HtmlUrl);
        try {
            // waitForSelector="table" - czekamy aż JS dorzuci tabele do DOM zanim pobierzemy HTML.
            String body = playwrightSession.fetchRenderedPage(wig20HtmlUrl, "table");
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Playwright zwrócił {} bajtów w {} ms", body.length(), elapsed);

            // Dump pełnego body do pliku - ułatwia porównanie z tym co widzi przeglądarka.
            Path dumpPath = dumpBodyToFile("wig20-html", body);
            if (dumpPath != null) {
                log.info("Pełne body HTML zapisane do: {}", dumpPath.toAbsolutePath());
            }

            // Diagnostyka strukturalna - po wyrenderowaniu przez Chromium powinniśmy widzieć tabele.
            String bodyLower = body.toLowerCase();
            int tableTagCount = countOccurrences(bodyLower, "<table");
            int trTagCount = countOccurrences(bodyLower, "<tr");
            log.info("HTML wyrenderowany: <table>={}, <tr>={}", tableTagCount, trTagCount);

            Document doc = Jsoup.parse(body, wig20HtmlUrl);
            int parsedTables = doc.select("table").size();
            if (parsedTables == 0 && tableTagCount > 0) {
                log.warn("Domyślny parser jsoup znalazł 0/{} tabel - próbuję parseBodyFragment",
                        tableTagCount);
                doc = Jsoup.parseBodyFragment(body, wig20HtmlUrl);
            }

            List<QuoteRow> quotes = parseQuotesFromDocument(doc);

            if (quotes.isEmpty()) {
                String htmlSnippet = body.length() > 2000
                        ? body.substring(0, 2000) + "\n... (obcięto)"
                        : body;
                log.warn("Parser HTML nie znalazł tabeli notowań po Playwright. HTML length: {}, tabel <table>={}",
                        body.length(), tableTagCount);
                return ScrapeResult.failure(
                        "Strona zrenderowana przez Playwright, ale parser nie znalazł tabeli notowań. " +
                        "Sprawdź dump w target/stooq-dumps/ - czy stooq zmienił layout?",
                        wig20HtmlUrl, elapsed, htmlSnippet);
            }

            log.info("HTML sparsowany: {} wierszy w {} ms", quotes.size(), elapsed);
            return ScrapeResult.success(quotes, wig20HtmlUrl, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Błąd pobierania HTML przez Playwright ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return ScrapeResult.failure(
                    "Playwright error: " + e.getClass().getSimpleName() + " - " + e.getMessage(),
                    wig20HtmlUrl, elapsed, null);
        }
    }

    /**
     * Parsuje CSV ze stooqa do listy QuoteRow.
     * Format (f=sd2t2ohlcv&h): Symbol,Data,Czas,Otwarcie,Najwyzszy,Najnizszy,Kurs,Wolumen
     *
     * Uwaga: ten CSV nie zawiera nazwy firmy ani zmiany procentowej - są tylko
     * w HTML. Dla tych pól zostawiamy null (name = ticker jako fallback).
     */
    private List<QuoteRow> parseCsv(String csvContent) {
        List<QuoteRow> result = new ArrayList<>();

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csvContent))) {

            for (CSVRecord record : parser) {
                QuoteRow row = parseCsvRecord(record);
                if (row != null) {
                    result.add(row);
                }
            }
        } catch (IOException e) {
            log.warn("Błąd parsowania CSV: {}", e.getMessage());
        }

        return result;
    }

    private QuoteRow parseCsvRecord(CSVRecord record) {
        try {
            String symbol = getField(record, "Symbol");
            if (symbol == null || symbol.isBlank()) return null;

            String closeText = getField(record, "Kurs", "Zamkniecie", "Close");
            String volumeText = getField(record, "Wolumen", "Volume");
            String time = getField(record, "Czas", "Time");

            Double close = parseDouble(closeText);
            Double volume = parseDouble(volumeText);

            return new QuoteRow(
                    symbol.toUpperCase(),
                    symbol,                     // brak nazwy w CSV - używamy ticker
                    close, closeText,
                    null, null,                 // zmiana % nie jest w tym CSV
                    volume, volumeText,
                    time
            );
        } catch (Exception e) {
            log.debug("Pominięto rekord CSV: {}", e.getMessage());
            return null;
        }
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

    private Double parseDouble(String text) {
        if (text == null || text.isBlank() || "N/A".equalsIgnoreCase(text)) return null;
        try {
            return Double.parseDouble(text.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static final DateTimeFormatter DUMP_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Path dumpBodyToFile(String prefix, String body) {
        try {
            Path targetDir = Paths.get("target", "stooq-dumps");
            Files.createDirectories(targetDir);
            Path file = targetDir.resolve(prefix + "-" + LocalDateTime.now().format(DUMP_TS) + ".html");
            Files.writeString(file, body == null ? "" : body, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            log.warn("Nie udało się zapisać dumpa body: {}", e.getMessage());
            return null;
        }
    }

    // ---- HTML parsing (fallback) --------------------------------------------

    private List<QuoteRow> parseQuotesFromDocument(Document doc) {
        List<QuoteRow> result = new ArrayList<>();

        log.info("Parsuję HTML: title='{}', body text length={}",
                doc.title(),
                doc.body() == null ? 0 : doc.body().text().length());

        Elements tables = doc.select("table");
        log.info("Znaleziono {} tabel na stronie", tables.size());

        // Zaloguj kandydatów - ułatwia diagnozę gdy stooq zmienia layout.
        logTableCandidates(tables);

        Element quotesTable = findQuotesTable(tables);
        if (quotesTable == null) {
            log.warn("Nie znaleziono tabeli notowań wśród {} tabel na stronie", tables.size());
            return result;
        }

        Elements rows = quotesTable.select("tr");
        log.info("Parsuję {} wierszy tabeli notowań", rows.size());

        for (Element row : rows) {
            QuoteRow quote = parseHtmlRow(row);
            if (quote != null) {
                result.add(quote);
            }
        }

        log.info("Z {} wierszy wyparsowano {} notowań", rows.size(), result.size());
        return result;
    }

    /**
     * Strategia detekcji:
     * 1) Tabela z największą liczbą linków do podstron stooq (/q/?s=TICKER) - to
     *    niezmiennik tabeli z komponentami indeksu. Niezależne od nagłówków które
     *    stooq potrafi zmieniać (Nazwa/Walor/Ticker itp.)
     * 2) Fallback: tabela z największą liczbą linków zawierających 's=' gdziekolwiek
     *    w href (szerszy wzorzec).
     */
    private Element findQuotesTable(Elements tables) {
        Element best = null;
        int bestScore = 0;

        for (Element table : tables) {
            int rowCount = table.select("tr").size();
            if (rowCount < 3) continue;
            int stockLinks = table.select("td a[href*=s=]").size();
            if (stockLinks > bestScore) {
                bestScore = stockLinks;
                best = table;
            }
        }

        if (best == null) {
            return null;
        }
        log.info("Wybrano tabelę notowań: {} linków s=, {} wierszy",
                bestScore, best.select("tr").size());
        return best;
    }

    private void logTableCandidates(Elements tables) {
        int printed = 0;
        for (int i = 0; i < tables.size() && printed < 10; i++) {
            Element table = tables.get(i);
            Elements rows = table.select("tr");
            int stockLinks = table.select("td a[href*=s=]").size();
            if (rows.size() < 3 && stockLinks == 0) continue;
            String header = rows.isEmpty() ? "" : rows.first().text().trim();
            if (header.length() > 120) header = header.substring(0, 120) + "...";
            log.info("Tabela[{}]: rows={}, stockLinks={}, pierwszy wiersz='{}'",
                    i, rows.size(), stockLinks, header);
            printed++;
        }
    }

    private QuoteRow parseHtmlRow(Element row) {
        Elements cells = row.select("td");
        if (cells.size() < 3) return null;

        try {
            Element firstCell = cells.get(0);
            Element nameLink = firstCell.selectFirst("a");

            String ticker = null;
            String name = firstCell.text().trim();

            if (nameLink != null) {
                String href = nameLink.attr("href");
                int sIndex = href.indexOf("s=");
                if (sIndex >= 0) {
                    String tickerPart = href.substring(sIndex + 2);
                    int endIndex = tickerPart.indexOf('&');
                    ticker = (endIndex > 0 ? tickerPart.substring(0, endIndex) : tickerPart)
                            .toUpperCase();
                }
                name = nameLink.text().trim();
            }

            if (ticker == null || ticker.isBlank()) return null;

            String priceText = cellText(cells, 1);
            String changePercentText = cellText(cells, cells.size() > 3 ? 3 : 2);
            String volumeText = cellText(cells, cells.size() > 4 ? 4 : 3);
            String timeText = cellText(cells, cells.size() - 1);

            Double price = parsePolishNumber(priceText);
            Double changePct = parsePolishNumber(changePercentText);
            Double volume = parsePolishNumber(volumeText);

            return new QuoteRow(
                    ticker, name,
                    price, priceText,
                    changePct, changePercentText,
                    volume, volumeText,
                    timeText
            );

        } catch (Exception e) {
            log.debug("Błąd parsowania wiersza HTML: {}", e.getMessage());
            return null;
        }
    }

    private String cellText(Elements cells, int index) {
        if (index < 0 || index >= cells.size()) return "";
        return cells.get(index).text().trim();
    }

    private Double parsePolishNumber(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text
                    .replace("\u00A0", "")
                    .replace(" ", "")
                    .replace(",", ".")
                    .replace("zł", "")
                    .replace("%", "")
                    .replace("+", "")
                    .trim();
            if (cleaned.isEmpty() || "-".equals(cleaned)) return null;
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Getter
    public static class ScrapeResult {
        private final boolean successful;
        private final List<QuoteRow> quotes;
        private final String errorMessage;
        private final String sourceUrl;
        private final long elapsedMillis;
        private final LocalDateTime fetchedAt;
        private final String htmlSnippet;

        private ScrapeResult(boolean successful, List<QuoteRow> quotes, String errorMessage,
                             String sourceUrl, long elapsedMillis, String htmlSnippet) {
            this.successful = successful;
            this.quotes = quotes == null ? List.of() : quotes;
            this.errorMessage = errorMessage;
            this.sourceUrl = sourceUrl;
            this.elapsedMillis = elapsedMillis;
            this.fetchedAt = LocalDateTime.now();
            this.htmlSnippet = htmlSnippet;
        }

        public static ScrapeResult success(List<QuoteRow> quotes, String url, long elapsed) {
            return new ScrapeResult(true, quotes, null, url, elapsed, null);
        }

        public static ScrapeResult failure(String error, String url, long elapsed, String html) {
            return new ScrapeResult(false, null, error, url, elapsed, html);
        }
    }
}
