package pl.rafal.stockpicker.model;

import java.util.List;

/**
 * Wiersz tabeli składu indeksu ze strony stooq.pl/t/?i=NNN&v=4 (widok stóp zwrotu).
 *
 * Stooq pokazuje dla każdej spółki w indeksie procentową stopę zwrotu w kilku
 * horyzontach czasowych (1d, 3d, 5d, 10d, 1m, 2m, 3m, 6m, YTD). Trzymamy je
 * jako listę par (label, value), bo template tylko je iteruje - dodanie albo
 * usunięcie horyzontu nie wymaga zmian w modelu.
 */
public record IndexComponentRow(
        String ticker,         // np. "PKO"
        String name,           // np. "PKOBP"
        List<ReturnEntry> returns,
        String time            // np. "17:00"
) {
    /**
     * Stopa zwrotu w jednym horyzoncie. Wartość trzymamy jako tekst dokładnie
     * tak jak ją wyświetla stooq ("+1.30%" / "-2.45%") - parser HTML nie musi
     * walczyć z lokalnymi formatami liczb, a UI pokazuje to 1:1.
     */
    public record ReturnEntry(String label, String value) {
        /** Klasa CSS Bootstrapa zależna od znaku: zielony / czerwony / neutralny. */
        public String cssClass() {
            if (value == null || value.isBlank()) return "text-muted";
            String trimmed = value.trim();
            if (trimmed.startsWith("+")) return "text-success";
            if (trimmed.startsWith("-")) return "text-danger";
            return "text-muted";
        }
    }
}
