package pl.rafal.stockpicker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import pl.rafal.stockpicker.dto.ScreeningRequest;
import pl.rafal.stockpicker.model.*;
import pl.rafal.stockpicker.service.ScreenerService;

import java.util.List;

/**
 * Kontroler Spring MVC obsługujący interakcję z użytkownikiem.
 *
 * Dwa endpointy:
 *   GET  /       -> formularz wyboru parametrów
 *   POST /screen -> wykonanie screeningu i zwrócenie wyników
 *
 * Dla czystości kodu, kontroler NIE zawiera logiki biznesowej. Jego rola:
 *   1. Przyjmuje parametry z HTTP i waliduje je (przez @Valid)
 *   2. Przekazuje je do serwisu
 *   3. Zwraca wynik jako widok Thymeleaf
 *
 * To jest klasyczna cienka warstwa kontrolera, którą łatwo testować
 * i łatwo wymienić na REST API jeśli kiedyś zechcesz zbudować mobilną apkę.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ScreeningController {

    private final ScreenerService screenerService;
    private final List<Stock> allStocks;

    /**
     * Wyświetla pusty formularz screeningu z wartościami domyślnymi.
     * Dodajemy do modelu listy wartości dla wyboru (dropdowny).
     */
    @GetMapping("/")
    public String showForm(Model model) {
        model.addAttribute("request", new ScreeningRequest());
        addFormOptions(model);
        return "form";
    }

    /**
     * Obsługa złożonego formularza.
     *
     * @Valid włącza walidację pól DTO, BindingResult przechwytuje błędy
     * walidacji. Gdy formularz jest niepoprawny, wracamy do formularza
     * z błędami zamiast wykonywać screening.
     */
    @PostMapping("/screen")
    public String runScreening(@Valid @ModelAttribute("request") ScreeningRequest request,
                                BindingResult bindingResult,
                                Model model) {
        if (bindingResult.hasErrors()) {
            log.warn("Formularz zawiera błędy walidacji: {}", bindingResult.getAllErrors());
            addFormOptions(model);
            return "form";
        }

        log.info("Uruchamiam screening: profil={}, horyzont={}, indeksy={}, kwota={}",
                request.getRiskProfile(), request.getHorizon(),
                request.getSelectedIndexes(), request.getInvestmentAmount());

        // Filtrujemy spółki do tych z wybranych indeksów
        List<Stock> stocksToAnalyze = allStocks.stream()
                .filter(s -> request.getSelectedIndexes().contains(s.index()))
                .toList();

        List<StockScore> results = screenerService.screen(
                stocksToAnalyze, request.getRiskProfile(), request.getHorizon());

        model.addAttribute("request", request);
        model.addAttribute("results", results);
        model.addAttribute("analyzedCount", stocksToAnalyze.size());
        return "results";
    }

    /**
     * Pomocnicza metoda dodająca do modelu wartości wyborów (enumy).
     * DRY - nie powtarzamy w dwóch metodach.
     */
    private void addFormOptions(Model model) {
        model.addAttribute("riskProfiles", RiskProfile.values());
        model.addAttribute("horizons", InvestmentHorizon.values());
        model.addAttribute("indexes", IndexType.values());
    }
}
