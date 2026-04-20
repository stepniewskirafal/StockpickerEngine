package pl.rafal.stockpicker.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import pl.rafal.stockpicker.model.IndexType;
import pl.rafal.stockpicker.model.InvestmentHorizon;
import pl.rafal.stockpicker.model.RiskProfile;

import java.util.Set;

/**
 * DTO reprezentujące parametry screeningu wybrane przez użytkownika na formularzu.
 *
 * Używam klasycznego POJO z Lombok @Data zamiast Java record, bo Thymeleaf
 * do poprawnego bindowania formularza wymaga setterów - rekordy są immutable
 * i nie mają setterów, więc binding nie działa.
 *
 * Adnotacje @NotNull i @Positive włączają walidację Spring - gdy użytkownik
 * wyśle formularz z pustym polem, Spring automatycznie zwróci błąd walidacji
 * zamiast propagować niepełne dane do logiki biznesowej.
 */
@Data
public class ScreeningRequest {

    @NotNull(message = "Wybierz profil ryzyka")
    private RiskProfile riskProfile = RiskProfile.AGGRESSIVE;

    @NotNull(message = "Wybierz horyzont czasowy")
    private InvestmentHorizon horizon = InvestmentHorizon.MEDIUM;

    /**
     * Użytkownik może zaznaczyć jeden lub więcej indeksów. Zestaw (Set)
     * zapewnia unikatowość i jest naturalnym typem dla tego pola.
     * Domyślnie wszystkie trzy - zgodnie z profilem agresywnym z pełnym spektrum.
     */
    @NotNull(message = "Wybierz co najmniej jeden indeks")
    private Set<IndexType> selectedIndexes = Set.of(
            IndexType.WIG20, IndexType.MWIG40, IndexType.SWIG80);

    @Positive(message = "Kwota musi być dodatnia")
    private double investmentAmount = 5000.0;
}
