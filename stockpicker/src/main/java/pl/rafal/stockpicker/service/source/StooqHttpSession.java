package pl.rafal.stockpicker.service.source;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Współdzielona sesja HTTP do stooq.pl.
 *
 * Jeden bean z jednym {@link HttpClient} i jednym {@link CookieManager} jest
 * reużywany przez wszystkie serwisy, które komunikują się ze stooq:
 * - StooqPriceDataSource (historyczne notowania CSV)
 * - StooqPublicPageService (aktualne notowania WIG20)
 *
 * Po co wspólna sesja?
 * ====================
 * 1. Wystarczy zalogować się raz - cookies sesyjne są dzielone.
 * 2. Zalogowany użytkownik nie widzi CMP consent wall - stooq od razu
 *    zwraca stronę z danymi zamiast nakładki z prośbą o zgodę na cookies.
 * 3. Zalogowany użytkownik pobiera CSV bez kodu captcha/apikey.
 * 4. SSL-trust-all i konfiguracja timeouta są w jednym miejscu.
 */
@Component
@Slf4j
public class StooqHttpSession {

    @Value("${stockpicker.stooq.login-url:https://stooq.pl/login/}")
    private String loginUrl;

    @Value("${stockpicker.stooq.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${stockpicker.stooq.login:}")
    private String stooqLogin;

    @Value("${stockpicker.stooq.password:}")
    private String stooqPassword;

    @Value("${stockpicker.stooq.login-enabled:false}")
    private boolean loginEnabled;

    @Value("${stockpicker.stooq.ssl-trust-all:false}")
    private boolean sslTrustAll;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final long LOGIN_RETRY_COOLDOWN_MS = 5 * 60 * 1000;

    private HttpClient httpClient;
    private CookieManager cookieManager;

    private volatile boolean isLoggedIn = false;
    private volatile long lastLoginAttemptTime = 0;

    @PostConstruct
    void initialize() {
        if (loginEnabled) {
            if (stooqLogin == null || stooqLogin.isBlank()
                    || stooqPassword == null || stooqPassword.isBlank()) {
                log.warn("Logowanie do stooq włączone, ale brak poświadczeń w konfiguracji");
                loginEnabled = false;
            } else {
                log.info("StooqHttpSession: logowanie włączone dla użytkownika: {}", stooqLogin);
            }
        } else {
            log.info("StooqHttpSession: logowanie wyłączone - tryb anonimowy (CMP consent wall będzie widoczny)");
        }

        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        HttpClient.Builder builder = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (sslTrustAll) {
            log.warn("!!! StooqHttpSession: WYŁĄCZONO WALIDACJĘ SSL - niezalecane w produkcji !!!");
            try {
                builder.sslContext(createInsecureSslContext());
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                log.error("Nie udało się skonfigurować niebezpiecznego SSL context", e);
            }
        }

        this.httpClient = builder.build();
    }

    /**
     * Pobiera body odpowiedzi jako string, zapewniając wcześniej że sesja
     * jest zalogowana (jeśli logowanie włączone). Domyślnie dekoduje jako UTF-8.
     */
    public HttpResponse<String> fetchString(String url) throws IOException, InterruptedException {
        return fetchString(url, StandardCharsets.UTF_8);
    }

    /**
     * Pobiera body odpowiedzi jako string z wybranym kodowaniem.
     * Stooq serwuje niektóre strony w ISO-8859-2 - wtedy podaj Charset.forName("ISO-8859-2").
     */
    public HttpResponse<String> fetchString(String url, Charset charset)
            throws IOException, InterruptedException {
        ensureLoggedIn();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/csv,*/*;q=0.8")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(charset));
    }

    /**
     * Informuje czy logowanie jest w ogóle włączone w konfiguracji.
     * Używane przez serwisy do decydowania o strategii fallback.
     */
    public boolean isLoginEnabled() {
        return loginEnabled;
    }

    /**
     * Oznacza sesję jako wygasłą - następne wywołanie ensureLoggedIn
     * wykona ponowne logowanie (z uwzględnieniem cooldown).
     * Wywoływane gdy serwis wykryje w odpowiedzi formularz logowania.
     */
    public void markSessionExpired() {
        if (isLoggedIn) {
            log.warn("StooqHttpSession: oznaczam sesję jako wygasłą");
            isLoggedIn = false;
        }
    }

    private void ensureLoggedIn() throws IOException, InterruptedException {
        if (!loginEnabled || isLoggedIn) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastLoginAttemptTime > 0 && now - lastLoginAttemptTime < LOGIN_RETRY_COOLDOWN_MS) {
            log.debug("StooqHttpSession: pomijam logowanie - cooldown po ostatniej próbie");
            return;
        }

        synchronized (this) {
            if (isLoggedIn) {
                return;
            }
            performLogin();
        }
    }

    private void performLogin() throws IOException, InterruptedException {
        lastLoginAttemptTime = System.currentTimeMillis();
        log.info("StooqHttpSession: loguję się do stooq jako {}", stooqLogin);

        String formBody = "a=" + URLEncoder.encode(stooqLogin, StandardCharsets.UTF_8)
                + "&b=" + URLEncoder.encode(stooqPassword, StandardCharsets.UTF_8);

        HttpRequest loginRequest = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .header("Referer", loginUrl)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(
                loginRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("Logowanie do stooq: HTTP " + response.statusCode());
        }

        String bodyLower = response.body() == null ? "" : response.body().toLowerCase();

        boolean loginFailed = bodyLower.contains("błędne hasło")
                || bodyLower.contains("bledne haslo")
                || bodyLower.contains("błędny login")
                || bodyLower.contains("nieprawidłowe");

        boolean loginSuccessful = bodyLower.contains("wyloguj")
                || bodyLower.contains("moje konto");

        if (loginFailed) {
            throw new IOException("Nieprawidłowe poświadczenia do stooq");
        }

        if (!loginSuccessful) {
            log.warn("StooqHttpSession: nie wykryto znacznika udanego logowania - kontynuuję");
        }

        isLoggedIn = true;
        log.info("StooqHttpSession: zalogowano pomyślnie. Zapisano {} ciasteczek.",
                cookieManager.getCookieStore().getCookies().size());
    }

    private static SSLContext createInsecureSslContext()
            throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }
}
