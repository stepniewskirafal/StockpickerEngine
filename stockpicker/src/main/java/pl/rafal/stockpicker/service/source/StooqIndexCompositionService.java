package pl.rafal.stockpicker.service.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import pl.rafal.stockpicker.model.IndexComponentRow;
import pl.rafal.stockpicker.model.IndexComponentRow.ReturnEntry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pobiera tabelę "stopy zwrotu < 1r" składu indeksu ze stooq.pl/t/?i=NNN&v=4.
 *
 * W przeciwieństwie do strony /q/i/?s=wig20 (chart indeksu) i /q/?s=wig20
 * (kwotowanie indeksu), URL /t/ to widok składu - 20 wierszy, jedna na spółkę,
 * z kolumnami stóp zwrotu w różnych horyzontach. Tabela ma stabilną klasę
 * .fth1 i wiersze id="r_N", dzięki czemu parser jest bezpośredni.
 *
 * Strona także siedzi za Google FC, więc używamy Playwright tak jak dla /q/i/.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StooqIndexCompositionService {

    private final PlaywrightStooqSession playwrightSession;

    @Value("${stockpicker.stooq.wig20-composition-url:https://stooq.pl/t/?i=532&v=4}")
    private String wig20CompositionUrl;

    /** Etykiety horyzontów w tej samej kolejności co kolumny w tabeli stooq. */
    private static final List<String> RETURN_LABELS =
            List.of("1d", "3d", "5d", "10d", "1m", "2m", "3m", "6m", "YTD");

    @Cacheable(value = "wig20Composition", key = "'wig20'")
    public CompositionResult fetchWig20Composition() {
        long startTime = System.currentTimeMillis();
        log.info("Pobieram skład WIG20 z {} (przez Playwright)", wig20CompositionUrl);
        try {
            String body = playwrightSession.fetchRenderedPage(wig20CompositionUrl, "table.fth1 tbody tr");
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Playwright zwrócił {} bajtów dla składu WIG20 w {} ms", body.length(), elapsed);

            Document doc = Jsoup.parse(body, wig20CompositionUrl);
            Element table = doc.selectFirst("table.fth1");
            if (table == null) {
                log.warn("Nie znaleziono table.fth1 - sprawdź dump w target/stooq-dumps/");
                return CompositionResult.failure(
                        "Nie znaleziono tabeli .fth1 - czy stooq zmienił layout?",
                        wig20CompositionUrl, elapsed);
            }

            List<IndexComponentRow> rows = parseRows(table);
            log.info("Z tabeli .fth1 wyparsowano {} wierszy składu indeksu", rows.size());
            if (rows.isEmpty()) {
                return CompositionResult.failure(
                        "Tabela .fth1 znaleziona, ale nie wyparsowano żadnego wiersza",
                        wig20CompositionUrl, elapsed);
            }
            return CompositionResult.success(rows, wig20CompositionUrl, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Błąd pobierania składu WIG20 ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return CompositionResult.failure(
                    "Playwright error: " + e.getClass().getSimpleName() + " - " + e.getMessage(),
                    wig20CompositionUrl, elapsed);
        }
    }

    /**
     * Parsuje wiersze id="r_N" z tabeli .fth1. Każdy wiersz to:
     *   <td>ticker</td> <td>nazwa</td>
     *   <td>1d</td>...<td>YTD</td> (po 9 kolumn ze stopami zwrotu)
     *   <td>czas</td> <td>favorites</td>
     *
     * Komórka stopy zwrotu zawiera dwa spany - ukryty z poprzednią ceną
     * i widoczny z procentem. Wybieramy widoczny po atrybucie style.
     */
    private List<IndexComponentRow> parseRows(Element table) {
        List<IndexComponentRow> result = new ArrayList<>();
        Elements rows = table.select("tbody tr[id^=r_]");
        for (Element tr : rows) {
            IndexComponentRow row = parseRow(tr);
            if (row != null) result.add(row);
        }
        return result;
    }

    private IndexComponentRow parseRow(Element tr) {
        try {
            Elements cells = tr.select("td");
            if (cells.size() < 12) {
                log.debug("Pomijam wiersz {} - tylko {} komórek (oczekiwano >=12)",
                        tr.id(), cells.size());
                return null;
            }
            String ticker = cells.get(0).text().trim();
            String name = cells.get(1).text().trim();
            if (ticker.isBlank()) return null;

            List<ReturnEntry> returns = new ArrayList<>(RETURN_LABELS.size());
            for (int i = 0; i < RETURN_LABELS.size(); i++) {
                String value = extractVisibleReturn(cells.get(2 + i));
                returns.add(new ReturnEntry(RETURN_LABELS.get(i), value));
            }
            String time = cells.size() > 11 ? cells.get(11).text().trim() : "";
            return new IndexComponentRow(ticker.toUpperCase(), name, returns, time);
        } catch (Exception e) {
            log.debug("Błąd parsowania wiersza {}: {}", tr.id(), e.getMessage());
            return null;
        }
    }

    /**
     * Komórka stopy zwrotu ma postać:
     *   <span style="display:none" id="aq_X_rrpN">31.02</span>
     *   <span id="aq_X_rrN"><a id="cN">+1.30%</a></span>
     * Zwracamy tekst widocznego spana (bez display:none).
     */
    private String extractVisibleReturn(Element cell) {
        for (Element span : cell.select("span")) {
            String style = span.attr("style").toLowerCase().replace(" ", "");
            if (!style.contains("display:none")) {
                String text = span.text().trim();
                if (!text.isEmpty()) return text;
            }
        }
        return cell.text().trim();
    }

    @Getter
    public static class CompositionResult {
        private final boolean successful;
        private final List<IndexComponentRow> rows;
        private final String errorMessage;
        private final String sourceUrl;
        private final long elapsedMillis;
        private final LocalDateTime fetchedAt;

        private CompositionResult(boolean successful, List<IndexComponentRow> rows,
                                  String errorMessage, String sourceUrl, long elapsedMillis) {
            this.successful = successful;
            this.rows = rows == null ? List.of() : rows;
            this.errorMessage = errorMessage;
            this.sourceUrl = sourceUrl;
            this.elapsedMillis = elapsedMillis;
            this.fetchedAt = LocalDateTime.now();
        }

        public static CompositionResult success(List<IndexComponentRow> rows, String url, long elapsed) {
            return new CompositionResult(true, rows, null, url, elapsed);
        }

        public static CompositionResult failure(String error, String url, long elapsed) {
            return new CompositionResult(false, null, error, url, elapsed);
        }

        public List<String> returnLabels() {
            return rows.isEmpty() ? RETURN_LABELS
                    : rows.get(0).returns().stream().map(ReturnEntry::label).toList();
        }
    }
}
