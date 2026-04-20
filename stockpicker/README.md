# Stock Picker Engine 80.40

Automatyczny screener techniczny spółek z WIG20, mWIG40 i sWIG80 na GPW.
Analizuje SMA(10/50/100/200), SMMA, RSI(14), MACD(12/26/9) i ZigZag na
interwale tygodniowym. Proponuje top 10 kandydatów zależnie od profilu
ryzyka i horyzontu inwestycyjnego.

Aplikacja ma dwie zakładki: **Screening** (pełen silnik scoringu) oraz
**Notowania WIG20** (diagnostyczna strona publicznych notowań).

## Wymagania

- Java 17 lub nowsza (testowane na 17 i 21)
- Maven 3.6+
- Dostęp do internetu (pobieranie danych ze stooq.pl)

## Szybki start - instrukcja krok po kroku

### 1. Skonfiguruj plik lokalny z poświadczeniami

Plik `src/main/resources/application-local.properties` jest już utworzony
z wartościami przykładowymi. Otwórz go i uzupełnij swoje prawdziwe dane:

```properties
stockpicker.stooq.login=TWOJ_LOGIN
stockpicker.stooq.password=TWOJE_HASLO
stockpicker.stooq.login-enabled=true

# WAŻNE dla sieci firmowej DXC/Zscaler/itp. - flaga dla SSL inspection
stockpicker.stooq.ssl-trust-all=true
```

Ten plik jest w `.gitignore`, więc nie trafi do repozytorium Git.

### 2. Uruchom aplikację

```bash
mvn clean spring-boot:run
```

Przy pierwszym uruchomieniu Maven pobierze zależności (Jsoup, Caffeine,
Apache Commons CSV) - potrwa 1-2 minuty. Następnie w logach powinny
pojawić się:

```
INFO  StockPickerApplication - The following 1 profile is active: "local"
INFO  StooqPriceDataSource - Logowanie do stooq włączone dla użytkownika: XYZ
INFO  StooqPublicPageService - === StooqPublicPageService: ssl-trust-all = true ===
WARN  StooqPublicPageService - !!! WYŁĄCZONO WALIDACJĘ SSL dla Jsoup !!!
INFO  StockPickerApplication - Started StockPickerApplication in X seconds
```

Jeśli nie widzisz tych linii, coś jest nie tak z profilem `local` - sprawdź
czy plik `application-local.properties` istnieje w `src/main/resources/`.

### 3. Otwórz aplikację w przeglądarce

Otwórz `http://localhost:8080/` - zobaczysz formularz screeningu.
Otwórz `http://localhost:8080/wig20` - zobaczysz diagnostyczną tabelę WIG20.

## Rozwiązywanie problemów

### Problem: błąd SSL "PKIX path building failed"

Ten błąd występuje w sieci firmowej z SSL inspection (DXC, Zscaler, Forcepoint).
Rozwiązanie ze strony Twojej aplikacji:

1. Otwórz `src/main/resources/application-local.properties`
2. Upewnij się że jest tam linia: `stockpicker.stooq.ssl-trust-all=true`
3. Zrestartuj aplikację
4. W logach startowych MUSISZ zobaczyć trzy WARN z wykrzyknikami `!!!`
5. Jeśli nie widzisz WARN-ów, problem jest z wczytaniem profilu `local`

Rozwiązanie długoterminowe (wymaga uprawnień administratora):
```powershell
# Wyeksportuj firmowy certyfikat CA z przeglądarki do pliku .cer
# Następnie zaimportuj do Java truststore:
cd "$env:JAVA_HOME\lib\security"
keytool -importcert -alias dxc-corp-ca -file "C:\sciezka\do\ca.cer" ^
  -keystore cacerts -storepass changeit
```

### Problem: "Template wig20 not found"

Plik `src/main/resources/templates/wig20.html` nie istnieje lub został źle
rozpakowany. Sprawdź czy istnieje pod dokładnie tą ścieżką. Jeśli nie,
rozpakuj ponownie archiwum projektu.

### Problem: "Ticker XYZ nie istnieje na stooq.pl"

Niektóre spółki z `StockRegistry.java` mogły zostać wycofane, zmienić ticker
lub nie być dostępne na stooq. Edytuj `StockRegistry.java` i usuń lub popraw
problematyczne tickery. Weryfikuj każdy ticker przez wejście w przeglądarce na
`https://stooq.pl/q/?s=TICKER` - jeśli widzisz wykres, ticker jest OK.

### Problem: port 8080 zajęty

```powershell
# Windows - zabij wszystkie procesy Java
taskkill /F /IM java.exe

# Lub zmień port w application.properties:
# server.port=8081
```

## Architektura

Projekt jest podzielony na warstwy zgodnie z zasadami SOLID:

```
pl.rafal.stockpicker/
├── config/
│   ├── CacheConfig.java          # Caffeine cache z 4h TTL
│   └── StockRegistry.java        # Skład indeksów WIG20/mWIG40/sWIG80
├── controller/
│   ├── ScreeningController.java  # GET / i POST /screen
│   └── Wig20Controller.java      # GET /wig20 - diagnostyka
├── dto/
│   └── ScreeningRequest.java     # Parametry formularza
├── model/                        # Rekordy Javy (immutable)
│   ├── Candle.java               # Świeca tygodniowa OHLCV
│   ├── IndexType.java            # Enum WIG20/MWIG40/SWIG80
│   ├── IndicatorSnapshot.java    # Wyniki wskaźników
│   ├── InvestmentHorizon.java    # Enum SHORT/MEDIUM/LONG
│   ├── QuoteRow.java             # Wiersz tabeli WIG20
│   ├── RiskProfile.java          # Enum DEFENSIVE/BALANCED/AGGRESSIVE
│   ├── Stock.java                # Metadane spółki
│   └── StockScore.java           # Wynik scoringu
├── service/
│   ├── indicator/                # Czyste kalkulatory matematyczne
│   │   ├── MacdCalculator.java
│   │   ├── MovingAverageCalculator.java
│   │   ├── RsiCalculator.java
│   │   └── ZigZagCalculator.java
│   ├── source/
│   │   ├── PriceDataSource.java             # Interfejs
│   │   ├── StooqPriceDataSource.java        # Implementacja CSV z logowaniem
│   │   └── StooqPublicPageService.java      # Scraping HTML przez Jsoup
│   ├── IndicatorService.java     # Orkiestrator obliczeń
│   └── ScreenerService.java      # Silnik scoringu
└── StockPickerApplication.java   # Main
```

## Algorytm scoringu

Każda spółka otrzymuje punkty (0-100) z czterech kategorii:

| Kategoria        | Max pkt | Co premiuje                                        |
|------------------|---------|----------------------------------------------------|
| Struktura SMA    | 30      | Cena > SMA200, SMA50 > SMA200, perfect stack       |
| Momentum MACD    | 25      | Świeży bullish cross, dodatni histogram            |
| RSI (warunkowe)  | 20      | Strefa siły zależna od profilu ryzyka              |
| ZigZag struktura | 15      | Higher highs + higher lows                         |

Kary (do -25 pkt) dla: przegrzania (cena > 50% nad SMA200), braku SMA200,
RSI > 75.

## Kluczowe konfiguracje

### application.properties (trafia do repo)

```properties
server.port=8080
spring.profiles.active=local

# Stooq - publiczne URL-e
stockpicker.stooq.base-url=https://stooq.pl/q/d/l/
stockpicker.stooq.login-url=https://stooq.pl/login/
stockpicker.stooq.wig20-url=https://stooq.pl/q/i/?s=wig20

# Timeouts i rate limiting
stockpicker.stooq.timeout-seconds=10
stockpicker.stooq.request-delay-ms=200

# Domyślnie wyłączone - nadpisywane przez application-local.properties
stockpicker.stooq.login-enabled=false
stockpicker.stooq.ssl-trust-all=false
```

### application-local.properties (NIE trafia do repo)

```properties
stockpicker.stooq.login=TWOJ_LOGIN
stockpicker.stooq.password=TWOJE_HASLO
stockpicker.stooq.login-enabled=true
stockpicker.stooq.ssl-trust-all=true
```

## Testy

```bash
mvn test
```

Pokrywają one kalkulatory wskaźników:
- RSI z danymi referencyjnymi z Wildera (1978)
- SMA na trywialnych seriach
- MACD na syntetycznym trendzie
- ZigZag na kontrolowanej sekwencji

## Planowane rozszerzenia

1. Eksport wyników do Excela/CSV
2. REST API (jsonowe endpointy)
3. Drugie źródło danych (Yahoo Finance) jako fallback
4. Backtesting na danych historycznych
5. Detekcja świeżości MACD cross
6. Wolumen jako dodatkowy sygnał confirmation
7. Dynamiczne pobieranie składu indeksów z GPW

## Zastrzeżenia

Aplikacja ma charakter **informacyjno-edukacyjny** i nie stanowi rekomendacji
inwestycyjnej. Scoring jest heurystyką techniczną - nie uwzględnia fundamentów
spółki ani makroekonomii. Decyzje inwestycyjne podejmujesz na własną
odpowiedzialność.
