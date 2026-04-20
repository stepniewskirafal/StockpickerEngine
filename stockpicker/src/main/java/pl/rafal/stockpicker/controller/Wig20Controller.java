package pl.rafal.stockpicker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pl.rafal.stockpicker.service.source.StooqPublicPageService;
import pl.rafal.stockpicker.service.source.StooqPublicPageService.ScrapeResult;

/**
 * Kontroler zakładki WIG20 - wyświetla publiczne notowania ze stooq.pl.
 *
 * Zakładka służy głównie do weryfikacji, że aplikacja ma poprawne połączenie
 * ze stooq. Jeśli tutaj widać dane - cała reszta (screening, logowanie) ma
 * dobre podstawy. Jeśli tutaj jest błąd - najpierw trzeba naprawić środowisko.
 *
 * Parametr 'refresh' pozwala wymusić ponowne pobranie z pominięciem cache -
 * przydatne gdy testujemy zmiany konfiguracji SSL.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class Wig20Controller {

    private final StooqPublicPageService stooqPublicPageService;

    @GetMapping("/wig20")
    public String showWig20(Model model, @RequestParam(required = false) Boolean refresh) {
        // W prostej implementacji nie ma force-refresh (wymagałoby CacheManager).
        // Parametr refresh zostawiam na przyszłość - użytkownik może po prostu
        // zrestartować aplikację żeby wyczyścić cache.

        ScrapeResult result = stooqPublicPageService.fetchWig20();

        model.addAttribute("result", result);
        model.addAttribute("quoteCount", result.getQuotes().size());
        return "wig20";
    }
}
