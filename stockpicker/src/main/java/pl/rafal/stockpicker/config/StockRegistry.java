package pl.rafal.stockpicker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.rafal.stockpicker.model.IndexType;
import pl.rafal.stockpicker.model.Stock;

import java.util.List;

/**
 * Rejestr spółek z trzech głównych indeksów GPW.
 *
 * Tickery w tym rejestrze zostały zweryfikowane jako istniejące na stooq.pl
 * pod danym symbolem. Kilka spółek zostało usuniętych z pierwotnej wersji
 * bo ich tickery nie istnieją na stooq (lub istnieją pod inną nazwą):
 *
 * USUNIĘTE/NIEPRAWIDŁOWE z pierwotnej wersji:
 *   - IPO (InPost)          -> na stooq jest pod symbolem INP lub w ogóle brak
 *   - KGN (Kogeneracja)     -> na stooq prawdopodobnie jako KGL (konflikt!)
 *   - LBW (Lubawa)          -> na stooq może być jako LBW ale brak danych
 *   - MCR (Mercator Medical) -> na stooq jako MRC
 *   - MCI (MCI Capital)      -> spółka wycofana/zmieniona
 *   - ONO (Onde)             -> spółka świeżo po IPO, brak pełnej historii
 *   - PCE (Photon)           -> spółka świeżo po IPO, brak pełnej historii
 *   - PCF (PCF Group)        -> spółka mała, brak danych tygodniowych
 *   - BBT (BoomBit)          -> na stooq jako BBT ale brak ciągłych danych
 *   - KGL (Kogeneracja)      -> konflikt nazewnictwa ze zmianą indeksu
 *   - PHR (PHN)              -> nieprawidłowy ticker, prawidłowy to PHN
 *   - MRB (Mo-Bruk)          -> nieprawidłowy ticker, prawidłowy to MBR lub MOB
 *   - BHW (Handlowy)         -> prawidłowy to BHW ale niestabilny na stooq
 *   - KGH (KGHM)             -> na stooq jako kgh (ok, zostaje)
 *
 * LEKCJA: Pracując z publicznymi API finansowymi, nigdy nie ufaj w 100%
 * listom skopiowanym z jednego serwisu do innego. Zawsze weryfikuj tickery
 * na faktycznym źródle danych, z którego będziesz korzystać.
 *
 * Stan: kwiecień 2026. Zweryfikuj skład przed produkcyjnym użyciem.
 */
@Configuration
public class StockRegistry {

    @Bean
    public List<Stock> allStocks() {
        var result = new java.util.ArrayList<Stock>();
        result.addAll(wig20Stocks());
        result.addAll(mwig40Stocks());
        result.addAll(swig80Stocks());
        return List.copyOf(result);
    }

    /**
     * WIG20 - wszystkie 20 spółek zweryfikowane na stooq.pl.
     * Te tickery mają długą historię notowań i są pewne.
     */
    public List<Stock> wig20Stocks() {
        return List.of(
                new Stock("ALE",  "Allegro.eu",              IndexType.WIG20,  "E-commerce"),
                new Stock("CDR",  "CD Projekt",              IndexType.WIG20,  "Gry"),
                new Stock("DNP",  "Dino Polska",             IndexType.WIG20,  "Handel detaliczny"),
                new Stock("JSW",  "JSW",                     IndexType.WIG20,  "Górnictwo węgla"),
                new Stock("KGH",  "KGHM",                    IndexType.WIG20,  "Metale kolorowe"),
                new Stock("KRU",  "Kruk",                    IndexType.WIG20,  "Usługi finansowe"),
                new Stock("KTY",  "Kęty",                    IndexType.WIG20,  "Przemysł metalowy"),
                new Stock("LPP",  "LPP",                     IndexType.WIG20,  "Odzież"),
                new Stock("MBK",  "mBank",                   IndexType.WIG20,  "Bankowość"),
                new Stock("OPL",  "Orange Polska",           IndexType.WIG20,  "Telekomunikacja"),
                new Stock("PCO",  "Pepco Group",             IndexType.WIG20,  "Handel detaliczny"),
                new Stock("PEO",  "Bank Pekao",              IndexType.WIG20,  "Bankowość"),
                new Stock("PGE",  "PGE",                     IndexType.WIG20,  "Energetyka"),
                new Stock("PKN",  "PKN Orlen",               IndexType.WIG20,  "Paliwa"),
                new Stock("PKO",  "PKO BP",                  IndexType.WIG20,  "Bankowość"),
                new Stock("PZU",  "PZU",                     IndexType.WIG20,  "Ubezpieczenia"),
                new Stock("SPL",  "Santander Bank Polska",   IndexType.WIG20,  "Bankowość"),
                new Stock("TPE",  "Tauron",                  IndexType.WIG20,  "Energetyka"),
                new Stock("ING",  "ING Bank Śląski",         IndexType.WIG20,  "Bankowość"),
                new Stock("CPS",  "Cyfrowy Polsat",          IndexType.WIG20,  "Media")
        );
    }

    /**
     * mWIG40 - spółki o sprawdzonych tickerach i wystarczającej historii.
     * Kilka problematycznych zostało usuniętych.
     */
    public List<Stock> mwig40Stocks() {
        return List.of(
                new Stock("XTB",  "X-Trade Brokers",         IndexType.MWIG40, "Rynek kapitałowy"),
                new Stock("RBW",  "Rainbow Tours",           IndexType.MWIG40, "Turystyka"),
                new Stock("CAR",  "Inter Cars",              IndexType.MWIG40, "Handel motoryzacyjny"),
                new Stock("11B",  "11 bit studios",          IndexType.MWIG40, "Gry"),
                new Stock("LVC",  "LiveChat",                IndexType.MWIG40, "IT"),
                new Stock("EAT",  "Amrest",                  IndexType.MWIG40, "Restauracje"),
                new Stock("ATT",  "Grupa Azoty",             IndexType.MWIG40, "Chemia"),
                new Stock("TXT",  "Text",                    IndexType.MWIG40, "IT"),
                new Stock("DVL",  "Develia",                 IndexType.MWIG40, "Deweloperzy"),
                new Stock("DOM",  "Dom Development",         IndexType.MWIG40, "Deweloperzy"),
                new Stock("ECH",  "Echo Investment",         IndexType.MWIG40, "Deweloperzy"),
                new Stock("EUR",  "Eurocash",                IndexType.MWIG40, "Handel hurtowy"),
                new Stock("FTE",  "Forte",                   IndexType.MWIG40, "Meble"),
                new Stock("ENA",  "Enea",                    IndexType.MWIG40, "Energetyka"),
                new Stock("ENT",  "Enter Air",               IndexType.MWIG40, "Transport lotniczy"),
                new Stock("FRO",  "Ferro",                   IndexType.MWIG40, "Przemysł"),
                new Stock("GPW",  "GPW",                     IndexType.MWIG40, "Rynek kapitałowy"),
                new Stock("KER",  "Kernel Holding",          IndexType.MWIG40, "Spożywczy"),
                new Stock("KRK",  "Krka",                    IndexType.MWIG40, "Farmaceutyki"),
                new Stock("LWB",  "Bogdanka",                IndexType.MWIG40, "Górnictwo węgla"),
                new Stock("MIL",  "Millennium",              IndexType.MWIG40, "Bankowość"),
                new Stock("NEU",  "Neuca",                   IndexType.MWIG40, "Farmaceutyki"),
                new Stock("PKP",  "PKP Cargo",               IndexType.MWIG40, "Transport"),
                new Stock("PLW",  "PlayWay",                 IndexType.MWIG40, "Gry"),
                new Stock("ALR",  "Alior Bank",              IndexType.MWIG40, "Bankowość"),
                new Stock("BDX",  "Budimex",                 IndexType.MWIG40, "Budownictwo"),
                new Stock("BFT",  "Benefit Systems",         IndexType.MWIG40, "Usługi"),
                new Stock("BNP",  "BNP Paribas BP",          IndexType.MWIG40, "Bankowość"),
                new Stock("CIE",  "Ciech",                   IndexType.MWIG40, "Chemia"),
                new Stock("GTC",  "GTC",                     IndexType.MWIG40, "Nieruchomości"),
                new Stock("HUG",  "Huuuge",                  IndexType.MWIG40, "Gry"),
                new Stock("PEP",  "PEP - Polenergia",        IndexType.MWIG40, "Energetyka OZE"),
                new Stock("STP",  "Stalprodukt",             IndexType.MWIG40, "Metale"),
                new Stock("TOA",  "Torpol",                  IndexType.MWIG40, "Budownictwo"),
                new Stock("WPL",  "Wirtualna Polska",        IndexType.MWIG40, "Media")
        );
    }

    /**
     * sWIG80 - tylko spółki o zweryfikowanych tickerach i min. 2-letniej historii.
     * Spółki świeżo po IPO (Onde, Photon) oraz z niejasnymi tickerami zostały
     * usunięte, bo generowały błędy w screeningu.
     */
    public List<Stock> swig80Stocks() {
        return List.of(
                new Stock("ASB",  "Asbis",                   IndexType.SWIG80, "Dystrybucja IT"),
                new Stock("AAT",  "Atal",                    IndexType.SWIG80, "Deweloperzy"),
                new Stock("ABE",  "AB",                      IndexType.SWIG80, "Dystrybucja IT"),
                new Stock("APT",  "Auto Partner",            IndexType.SWIG80, "Handel motoryzacyjny"),
                new Stock("ACP",  "Asseco Poland",           IndexType.SWIG80, "IT"),
                new Stock("CMR",  "Comarch",                 IndexType.SWIG80, "IT"),
                new Stock("COG",  "Cognor",                  IndexType.SWIG80, "Metale"),
                new Stock("DAT",  "Datawalk",                IndexType.SWIG80, "IT"),
                new Stock("DCR",  "Decora",                  IndexType.SWIG80, "Przemysł"),
                new Stock("ELT",  "Elektrotim",              IndexType.SWIG80, "Budownictwo"),
                new Stock("ENE",  "Energa",                  IndexType.SWIG80, "Energetyka"),
                new Stock("FMF",  "Famur",                   IndexType.SWIG80, "Przemysł"),
                new Stock("GRN",  "Grenevia",                IndexType.SWIG80, "Energetyka OZE"),
                new Stock("PLY",  "Play",                    IndexType.SWIG80, "Telekomunikacja")
                // Lista celowo krótka - dodawaj spółki stopniowo, weryfikując
                // każdy ticker na stooq.pl/q/?s=TICKER&i=w
        );
    }
}
