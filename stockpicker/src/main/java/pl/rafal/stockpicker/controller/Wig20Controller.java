package pl.rafal.stockpicker.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.rafal.stockpicker.service.source.StooqIndexCompositionService;
import pl.rafal.stockpicker.service.source.StooqIndexCompositionService.CompositionResult;

/** Kontroler zakładki "Skład WIG20" - 20 spółek + ich stopy zwrotu w 9 horyzontach. */
@Controller
@RequiredArgsConstructor
public class Wig20Controller {

    private final StooqIndexCompositionService compositionService;

    @GetMapping("/wig20-sklad")
    public String showComposition(Model model) {
        CompositionResult result = compositionService.fetchWig20Composition();
        model.addAttribute("result", result);
        model.addAttribute("rowCount", result.getRows().size());
        model.addAttribute("returnLabels", result.returnLabels());
        return "wig20-sklad";
    }
}
