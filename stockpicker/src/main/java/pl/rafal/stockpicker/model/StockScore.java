package pl.rafal.stockpicker.model;

import java.util.List;

/**
 * Reprezentuje wynik oceny jednej spółki przez silnik screeningu.
 *
 * Zawiera trzy rzeczy:
 * 1. Referencję do spółki i jej surowe wskaźniki (dla pełnej transparentności)
 * 2. Numeryczny score 0-100 (dla sortowania i ranking)
 * 3. Listę ludzko-czytelnych sygnałów (dla wyjaśnienia dlaczego taki score)
 *
 * Dlaczego lista sygnałów zamiast prostej oceny "KUPUJ"/"SPRZEDAJ"?
 * Bo traktujemy użytkownika jak dorosłego inwestora. On widzi nie tylko werdykt,
 * ale też argumenty, na podstawie których może zgodzić się lub nie. To kluczowe
 * dla profilu agresywnego, gdzie użytkownik może chcieć ryzyka, które algorytm
 * oznaczyłby jako zbyt duże.
 */
public record StockScore(
        Stock stock,
        IndicatorSnapshot indicators,
        double score,                    // 0-100, wyżej znaczy lepiej dla strategii byczej
        List<String> bullishSignals,     // sygnały popierające kupno
        List<String> warnings            // ostrzeżenia (przegrzanie, brak wolumenu itp.)
) {
    /**
     * Heurystyczna klasyfikacja siły sygnału na podstawie score.
     * Progi wzięte z empirycznego obserwowania jak zachowują się spółki
     * o różnych scorach w horyzoncie 1-3 miesięcy.
     */
    public String signalStrength() {
        if (score >= 80) return "BARDZO SILNY";
        if (score >= 65) return "SILNY";
        if (score >= 50) return "UMIARKOWANY";
        if (score >= 35) return "SŁABY";
        return "BRAK SYGNAŁU";
    }
}
