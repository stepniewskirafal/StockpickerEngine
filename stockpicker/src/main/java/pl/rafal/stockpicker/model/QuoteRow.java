package pl.rafal.stockpicker.model;

/**
 * Pojedynczy wiersz tabeli notowań z publicznej strony stooq.pl.
 *
 * Przechowuje zarówno wartości numeryczne (do dalszego przetwarzania) jak
 * i oryginalny tekst (rawText) do wyświetlenia. Dzięki temu widok pokazuje
 * liczby w tym samym formacie co stooq (polskie "108,50 zł", "1 234 567"),
 * a jednocześnie aplikacja ma wartości liczbowe do sortowania i analizy.
 *
 * Używam Double zamiast double, bo niektóre pola mogą być nieobecne dla
 * nieaktywnych spółek (np. niedawno notowane na rynku głównym). Null oznacza
 * "brak danych", 0.0 oznaczałoby "dokładnie zero", co jest fundamentalną różnicą.
 */
public record QuoteRow(
        String ticker,              // np. "ALE" - kod GPW
        String name,                // np. "Allegro.eu" - pełna nazwa
        Double lastPrice,           // cena ostatnia
        String lastPriceText,       // np. "30,08" - oryginalny tekst ze stooq
        Double changePercent,       // zmiana % np. -0.52
        String changePercentText,   // np. "-0.52%" do wyświetlenia
        Double volume,              // wolumen obrotu (sztuk akcji)
        String volumeText,          // np. "1 234 567"
        String time                 // czas ostatniej aktualizacji, np. "17:05"
) {
    /**
     * Pomocnicza metoda dla widoku - zwraca klasę CSS zależną od zmiany procentowej.
     * Bootstrap ma predefiniowane kolory: text-success (zielony), text-danger (czerwony).
     * Dla zmian bliskich zera (< 0.1%) użyjemy neutralnego text-muted.
     */
    public String changeCssClass() {
        if (changePercent == null) return "text-muted";
        if (changePercent > 0.1) return "text-success";
        if (changePercent < -0.1) return "text-danger";
        return "text-muted";
    }
}
