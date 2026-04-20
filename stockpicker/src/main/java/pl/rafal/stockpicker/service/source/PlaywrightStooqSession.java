package pl.rafal.stockpicker.service.source;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
// TimeoutError extends PlaywrightException - jest używany przez Playwright wewnętrznie,
// w try/catch łapiemy tylko bazowy typ.
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Sesja Playwright do scrapowania stron stooq.pl, które są zza CMP
 * (Google Funding Choices). Zwykły HttpClient dostaje tylko ~200KB JS
 * loader-a Funding Choices i nigdy nie widzi prawdziwej zawartości,
 * bo całe DOM jest dorzucane przez JavaScript po akceptacji zgody.
 *
 * Strategia
 * =========
 * - Jeden Browser na całą aplikację (lifecycle Spring bean).
 * - Trwały kontekst zapisywany na dysku (target/playwright-data) - cookies
 *   FCCDCF (consent), PHPSESSID, cookie_user przeżywają restart aplikacji,
 *   więc consent wall pokazuje się tylko raz na maszynę.
 * - Każde zapytanie tworzy nowy Page w tym kontekście, nawiguje, czeka
 *   na zawartość, zwraca wyrenderowany HTML.
 *
 * Pierwsze uruchomienie pobiera Chromium do ~/.cache/ms-playwright/.
 */
@Component
@Slf4j
public class PlaywrightStooqSession {

    @Value("${stockpicker.playwright.user-data-dir:target/playwright-data}")
    private String userDataDirPath;

    @Value("${stockpicker.playwright.headless:true}")
    private boolean headless;

    @Value("${stockpicker.playwright.navigation-timeout-ms:15000}")
    private double navigationTimeoutMs;

    @Value("${stockpicker.playwright.wait-for-selector-timeout-ms:8000}")
    private double waitSelectorTimeoutMs;

    @Value("${stockpicker.playwright.consent-click-timeout-ms:3000}")
    private double consentClickTimeoutMs;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Selektory przycisków akceptacji zgody. Stooq używa Google FC, ale w razie czego pokrywamy też inne CMP. */
    private static final List<String> CONSENT_ACCEPT_SELECTORS = List.of(
            "button:has-text('AKCEPTUJĘ')",
            "button:has-text('Akceptuję')",
            "button:has-text('Akceptuj wszystkie')",
            "button:has-text('Akceptuj')",
            "button:has-text('Zgadzam się')",
            "button:has-text('Wyrażam zgodę')",
            "button:has-text('Zgoda')",
            "[aria-label*='Akcept']",
            ".fc-consent-root button.fc-cta-consent",
            "button[mode='primary']"
    );

    private Playwright playwright;
    private BrowserContext browserContext;

    @PostConstruct
    void initialize() {
        log.info("PlaywrightStooqSession: inicjalizacja Playwright (headless={}, userDataDir={})",
                headless, userDataDirPath);
        long start = System.currentTimeMillis();

        try {
            Path userDataDir = Paths.get(userDataDirPath).toAbsolutePath();
            Files.createDirectories(userDataDir);

            // Idempotentne - jeśli Chromium już jest, kończy się w sekundę.
            // Pierwsze uruchomienie ściąga ~170MB do ~/.cache/ms-playwright/.
            ensureBrowserInstalled();

            playwright = Playwright.create();

            // launchPersistentContext = jeden BrowserContext z cookies/localStorage zapisanymi
            // na dysku. Dzięki temu po pierwszej akceptacji consent wall stooq nas pamięta.
            browserContext = playwright.chromium().launchPersistentContext(
                    userDataDir,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(headless)
                            .setUserAgent(USER_AGENT)
                            .setLocale("pl-PL")
                            .setTimezoneId("Europe/Warsaw")
                            .setViewportSize(1366, 768)
                            .setIgnoreHTTPSErrors(true)
            );

            browserContext.setDefaultNavigationTimeout(navigationTimeoutMs);
            browserContext.setDefaultTimeout(waitSelectorTimeoutMs);

            log.info("PlaywrightStooqSession: gotowy w {} ms (Chromium pobrany jeśli pierwsze uruchomienie)",
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("PlaywrightStooqSession: nie udało się zainicjalizować Playwright. " +
                    "Czy ściągnięto Chromium? Uruchom: 'mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args=\"install chromium\"'",
                    e);
            cleanupOnFailure();
            throw new IllegalStateException("Playwright init failed", e);
        }
    }

    /**
     * Otwiera nową stronę, nawiguje pod podany URL, ogarnia consent wall jeśli się
     * pojawi, czeka na element pasujący do waitForSelector (jeśli != null) i zwraca
     * gotowy HTML zrenderowany przez przeglądarkę.
     *
     * @param url adres do pobrania
     * @param waitForSelector CSS selektor który musi się pojawić zanim zwrócimy HTML;
     *                        null = po prostu czekamy na networkidle
     * @return HTML strony (po wykonaniu JS)
     */
    public String fetchRenderedPage(String url, String waitForSelector) {
        if (browserContext == null) {
            throw new IllegalStateException("PlaywrightStooqSession nie jest zainicjalizowany");
        }
        long start = System.currentTimeMillis();
        Page page = browserContext.newPage();
        try {
            log.info("Playwright: nawigacja do {}", url);
            page.navigate(url);

            handleConsentWallIfPresent(page);

            if (waitForSelector != null && !waitForSelector.isBlank()) {
                try {
                    page.waitForSelector(waitForSelector,
                            new Page.WaitForSelectorOptions().setTimeout(waitSelectorTimeoutMs));
                    log.debug("Playwright: doczekano się selektora '{}'", waitForSelector);
                } catch (TimeoutError e) {
                    log.warn("Playwright: timeout czekając na selektor '{}' - zwracam co jest", waitForSelector);
                }
            } else {
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(waitSelectorTimeoutMs));
                } catch (TimeoutError e) {
                    log.debug("Playwright: timeout na NETWORKIDLE - kontynuuję");
                }
            }

            String content = page.content();
            log.info("Playwright: {} -> {} bajtów HTML w {} ms",
                    url, content.length(), System.currentTimeMillis() - start);
            return content;
        } finally {
            try { page.close(); } catch (PlaywrightException ignored) {}
        }
    }

    private void handleConsentWallIfPresent(Page page) {
        for (String selector : CONSENT_ACCEPT_SELECTORS) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0 && locator.isVisible()) {
                    log.info("Playwright: wykryto consent wall, klikam '{}'", selector);
                    locator.click(new Locator.ClickOptions().setTimeout(consentClickTimeoutMs));
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(waitSelectorTimeoutMs));
                    return;
                }
            } catch (PlaywrightException e) {
                // TimeoutError extends PlaywrightException - jeden catch wystarczy.
                log.debug("Playwright: selektor '{}' nie zadziałał ({}), próbuję następny",
                        selector, e.getMessage());
            }
        }
        log.debug("Playwright: nie wykryto consent wall (lub już zaakceptowany)");
    }

    @PreDestroy
    void shutdown() {
        log.info("PlaywrightStooqSession: zamykam Playwright");
        cleanupOnFailure();
    }

    /**
     * Wywołuje wbudowane CLI Playwrighta żeby pobrać Chromium jeśli go nie ma.
     * Bez tego pierwsze launchPersistentContext() rzuca: "Executable doesn't exist".
     * Idempotentne - dla już zainstalowanej binarki kończy się szybko.
     */
    private void ensureBrowserInstalled() {
        try {
            log.info("PlaywrightStooqSession: sprawdzam/instaluję Chromium (idempotentnie)");
            com.microsoft.playwright.CLI.main(new String[]{"install", "chromium"});
        } catch (Throwable t) {
            // CLI.main potrafi rzucać/wywoływać System.exit - łapiemy Throwable żeby nie zabić startu.
            log.warn("PlaywrightStooqSession: instalacja Chromium rzuciła wyjątek ({}), kontynuuję - " +
                    "jeśli launch padnie, uruchom ręcznie: 'mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args=\"install chromium\"'",
                    t.getMessage());
        }
    }

    private void cleanupOnFailure() {
        if (browserContext != null) {
            try { browserContext.close(); } catch (Exception e) {
                log.warn("Błąd zamykania BrowserContext: {}", e.getMessage());
            }
            browserContext = null;
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception e) {
                log.warn("Błąd zamykania Playwright: {}", e.getMessage());
            }
            playwright = null;
        }
    }
}
