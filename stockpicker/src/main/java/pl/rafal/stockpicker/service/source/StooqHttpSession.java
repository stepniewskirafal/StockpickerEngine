package pl.rafal.stockpicker.service.source;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * Logowanie - odkrywanie formularza
 * ==================================
 * Stooq potrafi zmieniać URL akcji formularza oraz nazwy pól (a/b). Zamiast
 * zakładać stały endpoint, robimy GET strony logowania, wyciągamy z HTML
 * action= oraz nazwy pól input[type=text]/input[type=password], a dopiero
 * potem POST. Dzięki temu login nie pada jak stooq podmieni layout.
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
    private static final int BODY_SNIPPET_MAX = 600;

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

        log.debug("StooqHttpSession: GET {} (charset={}, loggedIn={}, cookies={})",
                url, charset.name(), isLoggedIn, cookieManager.getCookieStore().getCookies().size());

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(charset));

        String body = response.body();
        int bodyLen = body == null ? 0 : body.length();
        log.debug("StooqHttpSession: GET {} -> HTTP {} (body={} bajtów, content-type={})",
                url, response.statusCode(), bodyLen,
                response.headers().firstValue("content-type").orElse("?"));

        return response;
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
            log.debug("StooqHttpSession: pomijam logowanie - cooldown po ostatniej próbie ({}s temu)",
                    (now - lastLoginAttemptTime) / 1000);
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
        log.info("StooqHttpSession: rozpoczynam logowanie do stooq jako '{}' (loginUrl={})",
                stooqLogin, loginUrl);

        // Krok 1: GET strony logowania - zbieramy ciasteczka sesyjne/consent i poznajemy
        // prawdziwy URL akcji formularza. Stooq przy anonimowym GET może zwracać redirect
        // do consent wall, ale followRedirects=NORMAL za nas to ogarnia.
        LoginForm form = fetchLoginForm();

        // Krok 2: POST poświadczeń pod prawdziwy action URL z zachowanymi hidden fields.
        submitLoginForm(form);
    }

    private LoginForm fetchLoginForm() throws IOException, InterruptedException {
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        log.info("StooqHttpSession: GET {} (pobieram stronę logowania)", loginUrl);
        HttpResponse<String> resp = httpClient.send(
                getRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        String body = resp.body() == null ? "" : resp.body();
        log.info("StooqHttpSession: GET {} -> HTTP {} (body={} bajtów, content-type={}, finalUri={}, cookies={})",
                loginUrl, resp.statusCode(), body.length(),
                resp.headers().firstValue("content-type").orElse("?"),
                resp.uri(),
                cookieManager.getCookieStore().getCookies().size());
        logCookies("po GET login page");

        if (resp.statusCode() != 200) {
            log.warn("StooqHttpSession: snippet strony logowania: {}", snippet(body));
            throw new IOException("Nie udało się pobrać strony logowania: HTTP " + resp.statusCode()
                    + " dla " + loginUrl);
        }

        // URI po przekierowaniach - potrzebny żeby resolvować względny action.
        String baseUri = resp.uri().toString();
        LoginForm parsed = parseLoginForm(body, baseUri);

        log.info("StooqHttpSession: sparsowano formularz - action='{}', loginField='{}', passwordField='{}', hidden={}",
                parsed.actionUrl, parsed.loginFieldName, parsed.passwordFieldName, parsed.hiddenFields.size());
        for (Map.Entry<String, String> e : parsed.hiddenFields.entrySet()) {
            log.debug("StooqHttpSession: hidden field {}='{}'", e.getKey(), e.getValue());
        }
        return parsed;
    }

    private LoginForm parseLoginForm(String body, String baseUri) {
        // Domyślne wartości - jeśli parsowanie się nie powiedzie, strzelamy w konwencję stooq.
        LoginForm result = new LoginForm();
        result.actionUrl = loginUrl;
        result.loginFieldName = "a";
        result.passwordFieldName = "b";
        result.hiddenFields = new LinkedHashMap<>();

        try {
            Document doc = Jsoup.parse(body, baseUri);

            Element form = null;
            for (Element candidate : doc.select("form")) {
                if (!candidate.select("input[type=password]").isEmpty()) {
                    form = candidate;
                    break;
                }
            }

            if (form == null) {
                log.warn("StooqHttpSession: nie znaleziono formularza z input[type=password] na stronie logowania");
                log.debug("StooqHttpSession: snippet HTML: {}", snippet(body));
                return result;
            }

            // action - jsoup absUrl ogarnia resolvowanie względem baseUri.
            String actionAttr = form.attr("action");
            String absoluteAction = form.absUrl("action");
            if (absoluteAction != null && !absoluteAction.isBlank()) {
                result.actionUrl = absoluteAction;
            } else if (actionAttr != null && !actionAttr.isBlank() && !"#".equals(actionAttr)) {
                // Absolutny się nie powiódł (np. action="./") - zostawiamy loginUrl jako fallback.
                log.debug("StooqHttpSession: action='{}' nie zresolvował się do absolutnego URL - używam {}",
                        actionAttr, result.actionUrl);
            }

            // Wykryj nazwy pól login/hasło oraz wszystkie hidden inputs.
            Elements inputs = form.select("input");
            Element passwordInput = null;
            for (Element in : inputs) {
                if ("password".equalsIgnoreCase(in.attr("type"))) {
                    passwordInput = in;
                    break;
                }
            }
            if (passwordInput != null && !passwordInput.attr("name").isBlank()) {
                result.passwordFieldName = passwordInput.attr("name");
            }
            // Login field - pierwsze text/email/puste input przed polem hasła.
            for (Element in : inputs) {
                if (in == passwordInput) break;
                String type = in.attr("type");
                if (type.isBlank() || "text".equalsIgnoreCase(type) || "email".equalsIgnoreCase(type)) {
                    if (!in.attr("name").isBlank()) {
                        result.loginFieldName = in.attr("name");
                        break;
                    }
                }
            }
            // Hidden fields - musimy je odesłać, żeby serwer uznał POST za poprawny.
            for (Element in : inputs) {
                if ("hidden".equalsIgnoreCase(in.attr("type"))) {
                    String name = in.attr("name");
                    if (!name.isBlank()) {
                        result.hiddenFields.put(name, in.attr("value"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("StooqHttpSession: błąd parsowania formularza logowania: {} - używam domyślnych wartości",
                    e.getMessage());
        }
        return result;
    }

    private void submitLoginForm(LoginForm form) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.hiddenFields.entrySet()) {
            appendFormField(body, e.getKey(), e.getValue());
        }
        appendFormField(body, form.loginFieldName, stooqLogin);
        appendFormField(body, form.passwordFieldName, stooqPassword);

        HttpRequest loginRequest = HttpRequest.newBuilder()
                .uri(URI.create(form.actionUrl))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .header("Referer", loginUrl)
                .header("Origin", extractOrigin(loginUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        log.info("StooqHttpSession: POST {} (formFields={}, bodyBytes={})",
                form.actionUrl, countFields(body.toString()), body.length());

        HttpResponse<String> response = httpClient.send(
                loginRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        String respBody = response.body() == null ? "" : response.body();
        log.info("StooqHttpSession: POST {} -> HTTP {} (body={} bajtów, content-type={}, finalUri={}, cookies={})",
                form.actionUrl, response.statusCode(), respBody.length(),
                response.headers().firstValue("content-type").orElse("?"),
                response.uri(),
                cookieManager.getCookieStore().getCookies().size());
        logCookies("po POST login");

        if (response.statusCode() != 200) {
            log.warn("StooqHttpSession: snippet odpowiedzi błędu: {}", snippet(respBody));
            throw new IOException("Logowanie do stooq: HTTP " + response.statusCode()
                    + " dla " + form.actionUrl);
        }

        String bodyLower = respBody.toLowerCase();

        boolean loginFailed = bodyLower.contains("błędne hasło")
                || bodyLower.contains("bledne haslo")
                || bodyLower.contains("błędny login")
                || bodyLower.contains("nieprawidłowe");

        boolean loginSuccessful = bodyLower.contains("wyloguj")
                || bodyLower.contains("moje konto");

        if (loginFailed) {
            log.warn("StooqHttpSession: serwer zwrócił komunikat o błędnych poświadczeniach. Snippet: {}",
                    snippet(respBody));
            throw new IOException("Nieprawidłowe poświadczenia do stooq");
        }

        if (!loginSuccessful) {
            log.warn("StooqHttpSession: nie wykryto znacznika udanego logowania (brak 'wyloguj'/'moje konto'). Snippet: {}",
                    snippet(respBody));
        }

        isLoggedIn = true;
        log.info("StooqHttpSession: zalogowano pomyślnie. Zapisano {} ciasteczek.",
                cookieManager.getCookieStore().getCookies().size());
    }

    private void appendFormField(StringBuilder sb, String name, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
    }

    private int countFields(String body) {
        if (body.isEmpty()) return 0;
        return body.split("&").length;
    }

    private String extractOrigin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "https://stooq.pl";
        }
    }

    private String snippet(String body) {
        if (body == null) return "null";
        String trimmed = body.length() > BODY_SNIPPET_MAX
                ? body.substring(0, BODY_SNIPPET_MAX) + "...(obcięto " + (body.length() - BODY_SNIPPET_MAX) + " znaków)"
                : body;
        return trimmed.replace("\n", " ").replace("\r", " ");
    }

    private void logCookies(String phase) {
        if (!log.isDebugEnabled()) return;
        List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
        if (cookies.isEmpty()) {
            log.debug("StooqHttpSession: cookies {} - brak", phase);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (HttpCookie c : cookies) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.getName()).append("=").append(c.getValue() == null ? "" :
                    c.getValue().length() > 20 ? c.getValue().substring(0, 20) + "..." : c.getValue());
        }
        log.debug("StooqHttpSession: cookies {} ({}): {}", phase, cookies.size(), sb);
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

    /** Dane formularza logowania wyciągnięte z HTML strony /login/. */
    private static class LoginForm {
        String actionUrl;
        String loginFieldName;
        String passwordFieldName;
        Map<String, String> hiddenFields;
    }
}
