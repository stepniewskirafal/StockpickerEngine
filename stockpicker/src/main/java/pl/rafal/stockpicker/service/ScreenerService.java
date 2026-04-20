package pl.rafal.stockpicker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.rafal.stockpicker.model.*;
import pl.rafal.stockpicker.service.source.PriceDataSource;
import pl.rafal.stockpicker.service.source.PriceDataSource.DataSourceException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Silnik screeningu - iteruje po wszystkich spółkach z wybranych indeksów,
 * liczy wskaźniki techniczne i nadaje scoring od 0 do 100.
 *
 * To jest centralna klasa biznesowa aplikacji. Wszystko inne (kalkulatory,
 * źródła danych, kontrolery) to albo jej zależności, albo konsumenci wyniku.
 *
 * ALGORYTM SCORINGU
 * =================
 *
 * Każda spółka startuje z 0 punktów i może zdobyć do 100 w czterech kategoriach:
 *
 *   1. STRUKTURA SMA (do 30 pkt)
 *      +10 cena > SMA(200)
 *      +8  SMA(50) > SMA(200) (byczy układ długoterminowy)
 *      +7  cena > SMA(50)
 *      +5  cena > SMA(10) (świeży krótki trend)
 *
 *   2. MOMENTUM MACD (do 25 pkt)
 *      +15 byczy cross (MACD > signal, histogram > 0)
 *      +10 dodatnie i rosnące momentum
 *
 *   3. RSI (do 20 pkt, w zależności od strefy)
 *      Dla AGGRESSIVE: 55-70 = 20 pkt, 50-55 = 15 pkt, 70-75 = 12 pkt
 *      Dla BALANCED:   50-65 = 20 pkt, 45-50 = 12 pkt, 65-70 = 12 pkt
 *      Dla DEFENSIVE:  48-58 = 20 pkt, 45-48 lub 58-62 = 10 pkt
 *
 *   4. ZIGZAG STRUKTURA (do 15 pkt)
 *      +15 higher highs + higher lows (pełen byczy setup)
 *      +8  tylko higher highs LUB tylko higher lows
 *
 *   KARY (do -25 pkt)
 *      -10 cena rozgrzana (dystans od SMA200 > 50%)
 *      -5  brak SMA200 (za krótka historia)
 *      -10 RSI > 75 (strefa wykupienia)
 *
 * Scoring nie jest matematyką fizyczną - to heurystyka oparta na empirii.
 * Wagi można stroić backtestami na danych historycznych, ale te wartości
 * to rozsądny start oparty na klasycznej literaturze analizy technicznej.
 */
@Service
@RequiredArgsConstructor // Lombok generuje konstruktor z wstrzykiwaniem zależności
@Slf4j
public class ScreenerService {

    private final PriceDataSource priceDataSource;
    private final IndicatorService indicatorService;

    /**
     * Minimum 210 tygodni (~4 lata) żeby policzyć SMA(200) z sensownym
     * zapasem na MACD i inne wskaźniki. Dla spółek z krótszą historią
     * odpowiednie SMA będą null i stracą punkty w scoringu.
     */
    private static final int WEEKS_HISTORY_NEEDED = 210;

    /**
     * Ile spółek zwracamy użytkownikowi - top 10 to rozsądna dawka,
     * na której można zbudować skupioną analizę.
     */
    private static final int TOP_N = 10;

    /**
     * Uruchamia pełen screening dla wybranego zestawu spółek i profilu.
     *
     * Metoda jest zaprojektowana jako "best effort" - jeśli dla jakiejś spółki
     * stooq nie zwróci danych, logujemy ostrzeżenie i idziemy dalej zamiast
     * przerywać cały screening. Użytkownik dostanie wyniki tych spółek które
     * się udało przeanalizować.
     *
     * @param stocks lista spółek do przeanalizowania (filtrowana już wcześniej
     *               po wybranych indeksach)
     * @param profile profil ryzyka wpływający na wagi scoringu
     * @param horizon horyzont czasowy (obecnie używany do logowania, w przyszłości
     *                może modyfikować wagi MACD vs SMA)
     * @return lista top N spółek posortowanych malejąco po score
     */
    public List<StockScore> screen(List<Stock> stocks, RiskProfile profile,
                                    InvestmentHorizon horizon) {
        log.info("Startuję screening {} spółek dla profilu {} i horyzontu {}",
                stocks.size(), profile, horizon);

        List<StockScore> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (Stock stock : stocks) {
            try {
                StockScore score = analyzeStock(stock, profile, horizon);
                results.add(score);
                successCount++;
            } catch (DataSourceException e) {
                // Pojedyncza awaria spółki nie przerywa całego screeningu.
                // Logujemy i jedziemy dalej - lepiej dać użytkownikowi 138
                // wyników niż 0 z powodu jednego zepsutego tickera.
                log.warn("Pominięto spółkę {} z powodu błędu: {}",
                        stock.ticker(), e.getMessage());
                failCount++;
            } catch (Exception e) {
                // Catch-all na nieoczekiwane błędy - nie chcemy żeby jedno
                // NullPointerException zablokowało screening całego indeksu
                log.error("Nieoczekiwany błąd dla spółki {}", stock.ticker(), e);
                failCount++;
            }
        }

        log.info("Screening zakończony: {} sukces, {} niepowodzenie",
                successCount, failCount);

        // Sortujemy malejąco po score i obcinamy do top N
        return results.stream()
                .sorted(Comparator.comparingDouble(StockScore::score).reversed())
                .limit(TOP_N)
                .toList();
    }

    /**
     * Analizuje jedną spółkę - pobiera dane, liczy wskaźniki, wystawia score.
     * Wyciągnięte do osobnej metody dla czytelności i łatwiejszego testowania.
     */
    private StockScore analyzeStock(Stock stock, RiskProfile profile,
                                     InvestmentHorizon horizon) throws DataSourceException {
        // KROK 1: Pobranie danych historycznych
        List<Candle> candles = priceDataSource.fetchWeeklyCandles(
                stock.stooqSymbol(), WEEKS_HISTORY_NEEDED);

        if (candles.size() < 30) {
            // Spółka z mniej niż 30 tygodniami historii jest bezużyteczna
            // dla naszej analizy - nawet SMA(50) jej nie policzymy
            throw new DataSourceException(
                    "Za krótka historia dla " + stock.ticker() +
                    " (tylko " + candles.size() + " tygodni)");
        }

        // KROK 2: Obliczenie wskaźników
        IndicatorSnapshot indicators = indicatorService.calculateSnapshot(candles);

        // KROK 3: Scoring i budowa odpowiedzi
        return scoreStock(stock, indicators, profile);
    }

    /**
     * Przydziela punkty na podstawie wskaźników i profilu ryzyka.
     *
     * To jest najważniejsza metoda w aplikacji - to w niej implementujemy
     * naszą strategię inwestycyjną. Każda zmiana wag tutaj przekłada się
     * na inne rekomendacje. Traktujemy ją jak konfigurację strategii:
     * jawną, czytelną, łatwą do modyfikacji.
     */
    private StockScore scoreStock(Stock stock, IndicatorSnapshot ind, RiskProfile profile) {
        double score = 0.0;
        List<String> bullish = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // === KATEGORIA 1: Struktura SMA (max 30 pkt) ===
        if (ind.sma200() != null && ind.currentPrice() > ind.sma200()) {
            score += 10;
            bullish.add(String.format("Cena (%.2f) powyżej SMA200 (%.2f) - trend długoterminowy byczy",
                    ind.currentPrice(), ind.sma200()));
        } else if (ind.sma200() == null) {
            score -= 5;
            warnings.add("Brak SMA(200) - spółka ma za krótką historię notowań");
        }

        if (ind.sma50() != null && ind.sma200() != null && ind.sma50() > ind.sma200()) {
            score += 8;
            bullish.add("SMA(50) powyżej SMA(200) - byczy układ średnich długoterminowych");
        }

        if (ind.sma50() != null && ind.currentPrice() > ind.sma50()) {
            score += 7;
            bullish.add(String.format("Cena powyżej SMA50 (%.2f) - trend średnioterminowy byczy",
                    ind.sma50()));
        }

        if (ind.sma10() != null && ind.currentPrice() > ind.sma10()) {
            score += 5;
            bullish.add("Cena powyżej SMA10 - świeże krótkie momentum");
        }

        // Bonus za perfect bullish stack
        if (ind.hasPerfectBullishStack()) {
            bullish.add("★ IDEALNY BYCZY UKŁAD: cena > SMA10 > SMA50 > SMA100 > SMA200");
        }

        // === KATEGORIA 2: Momentum MACD (max 25 pkt) ===
        if (ind.macd() != null) {
            if (ind.macd().isBullishCross()) {
                score += 15;
                bullish.add(String.format("Byczy cross MACD (%.3f > sygnał %.3f)",
                        ind.macd().macdLine(), ind.macd().signalLine()));
            }
            // Dodatkowo premiujemy rosnący histogram jako oznakę przyspieszającego momentum
            if (ind.macd().histogram() != null && ind.macd().histogram() > 0) {
                score += 10;
                bullish.add("Dodatni histogram MACD - momentum po stronie byków");
            }
        }

        // === KATEGORIA 3: RSI (max 20 pkt) zależnie od profilu ===
        if (ind.rsi14() != null) {
            double rsi = ind.rsi14();
            double rsiScore = scoreRsi(rsi, profile);
            score += rsiScore;

            if (rsiScore >= 15) {
                bullish.add(String.format("RSI w optymalnej strefie: %.1f", rsi));
            } else if (rsi > 75) {
                score -= 10;
                warnings.add(String.format("RSI = %.1f - strefa silnego wykupienia, ryzyko korekty", rsi));
            } else if (rsi < 30) {
                warnings.add(String.format("RSI = %.1f - strefa wyprzedania (potencjalne dno lub slabość)", rsi));
            }
        }

        // === KATEGORIA 4: ZigZag struktura (max 15 pkt) ===
        if (ind.zigzag() != null) {
            if (ind.zigzag().isBullish()) {
                score += 15;
                bullish.add("ZigZag: higher highs + higher lows - struktura trendu wzrostowego");
            } else if (ind.zigzag().higherHighs() || ind.zigzag().higherLows()) {
                score += 8;
                bullish.add("ZigZag: częściowa byczy struktura (tylko HH lub tylko HL)");
            }
        }

        // === KARY (ostrzeżenia) ===
        double distance = ind.distanceFromSma200Percent();
        if (distance > 50) {
            score -= 10;
            warnings.add(String.format(
                    "Cena rozgrzana: +%.1f%% powyżej SMA200, ryzyko powrotu do średniej", distance));
        }

        // === MODYFIKATORY PROFILU RYZYKA ===
        // Agresywny profil dostaje bonus za silne momentum nawet przy przegrzaniu,
        // defensywny traci punkty za każde ryzyko
        score = applyProfileModifier(score, ind, profile, bullish, warnings);

        // Ogranicz score do zakresu 0-100 dla czytelności
        score = Math.max(0, Math.min(100, score));

        return new StockScore(stock, ind, score, bullish, warnings);
    }

    /**
     * Scoring RSI w zależności od profilu ryzyka.
     *
     * Dlaczego tak? Bo RSI "idealne" dla agresywnego tradera to inne niż dla
     * defensywnego. Agresywny lubi trenować momentum, więc tolerowane są
     * wyższe wartości. Defensywny boi się korekty, więc premiuje środek.
     */
    private double scoreRsi(double rsi, RiskProfile profile) {
        return switch (profile) {
            case AGGRESSIVE -> {
                if (rsi >= 55 && rsi <= 70) yield 20;    // ideał dla agresywnego
                if (rsi >= 50 && rsi < 55)  yield 15;
                if (rsi > 70 && rsi <= 75)  yield 12;    // tolerowane wykupienie
                if (rsi >= 40 && rsi < 50)  yield 8;
                yield 0;
            }
            case BALANCED -> {
                if (rsi >= 50 && rsi <= 65) yield 20;    // ideał dla zrównoważonego
                if (rsi >= 45 && rsi < 50)  yield 12;
                if (rsi > 65 && rsi <= 70)  yield 12;
                if (rsi >= 40 && rsi < 45)  yield 6;
                yield 0;
            }
            case DEFENSIVE -> {
                if (rsi >= 48 && rsi <= 58) yield 20;    // ideał dla defensywnego
                if (rsi >= 45 && rsi < 48)  yield 10;
                if (rsi > 58 && rsi <= 62)  yield 10;
                yield 0;
            }
        };
    }

    /**
     * Modyfikator scoringu zależny od profilu - dodaje zagregowane premie
     * lub kary, które wykraczają poza pojedyncze wskaźniki.
     */
    private double applyProfileModifier(double baseScore, IndicatorSnapshot ind,
                                         RiskProfile profile, List<String> bullish,
                                         List<String> warnings) {
        return switch (profile) {
            case AGGRESSIVE -> {
                // Agresywny szuka prędkości - bonus za silne momentum i pełen stack
                if (ind.hasPerfectBullishStack() && ind.macd() != null
                        && ind.macd().isBullishCross()) {
                    bullish.add("★★ Kombinacja PERFECT STACK + świeży MACD cross - sygnał premium");
                    yield baseScore + 5;
                }
                yield baseScore;
            }
            case DEFENSIVE -> {
                // Defensywny odrzuca wysokie zmienności - kara za duży dystans od SMA200
                if (ind.distanceFromSma200Percent() > 30) {
                    warnings.add("Profil defensywny: cena za daleko od SMA200, preferujemy bliżej średniej");
                    yield baseScore - 5;
                }
                yield baseScore;
            }
            default -> baseScore;
        };
    }
}
