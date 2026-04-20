package pl.rafal.stockpicker.model;

/**
 * Profil ryzyka inwestora - wpływa na wagi stosowane przez silnik scoringu.
 *
 * Każdy profil ma inną tolerancję na różne rodzaje sygnałów:
 *
 * DEFENSIVE - stawia na stabilność, preferuje:
 *   - RSI w przedziale 45-60 (środek, nie przegrzane)
 *   - Obecność SMA200 (długa historia notowań)
 *   - Niski dystans ceny od SMA200 (brak przegrzania)
 *   - Cena powyżej SMMA20 z mniejszą wagą
 *
 * BALANCED - zrównoważony, równe wagi dla większości czynników:
 *   - RSI 50-70
 *   - Wszystkie SMA byczo ułożone
 *   - Świeży bullish MACD cross
 *
 * AGGRESSIVE - maksymalny potencjał, preferuje:
 *   - Świeży MACD cross (najwyższa waga)
 *   - Higher highs/lows z ZigZag
 *   - Rosnące momentum (pozytywny histogram)
 *   - Toleruje RSI do 75 (blisko wykupienia)
 *   - Nawet pomija brak SMA200 (nowe spółki na giełdzie mogą być hot)
 */
public enum RiskProfile {
    DEFENSIVE("Defensywny"),
    BALANCED("Zrównoważony"),
    AGGRESSIVE("Agresywny");

    private final String displayName;

    RiskProfile(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
