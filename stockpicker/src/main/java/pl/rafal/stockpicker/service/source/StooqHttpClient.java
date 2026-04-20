package pl.rafal.stockpicker.service.source;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Cienki klient HTTP do publicznych endpointów stooq.pl, których nie blokuje
 * consent wall (np. {@code /q/d/l/?s=TICKER} - historyczne CSV per ticker).
 *
 * Strony za Google Funding Choices CMP (np. /q/i/, /t/) wymagają wykonania
 * JavaScriptu i obsługuje je {@link PlaywrightStooqSession}.
 *
 * Pod spodem java.net.http.HttpClient + ręczna dekompresja gzip/deflate, bo
 * Java nie dekompresuje automatycznie. Stooq wysyła RAW deflate (bez headera
 * zlib), więc dla deflate'u próbujemy zlib, potem raw.
 */
@Component
@Slf4j
public class StooqHttpClient {

    @Value("${stockpicker.stooq.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${stockpicker.stooq.ssl-trust-all:false}")
    private boolean sslTrustAll;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private HttpClient httpClient;

    @PostConstruct
    void initialize() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (sslTrustAll) {
            log.warn("StooqHttpClient: walidacja SSL wyłączona - tylko do sieci zaufanej (np. korporacyjny proxy z SSL inspection)");
            builder.sslContext(insecureSslContext());
        }
        this.httpClient = builder.build();
    }

    /**
     * Pobiera body GET-em jako tekst (UTF-8). Rzuca {@link IOException} na status != 2xx.
     */
    public String getBody(String url) throws IOException, InterruptedException {
        return getBody(url, StandardCharsets.UTF_8);
    }

    public String getBody(String url, Charset charset) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/csv,text/html;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "gzip, deflate, identity")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Stooq HTTP " + response.statusCode() + " dla " + url);
        }
        byte[] decoded = decodeBody(response);
        log.debug("StooqHttpClient: GET {} -> HTTP {} ({} bajtów po dekompresji)",
                url, response.statusCode(), decoded.length);
        return new String(decoded, charset);
    }

    private byte[] decodeBody(HttpResponse<byte[]> response) {
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) return new byte[0];
        String encoding = response.headers().firstValue("content-encoding")
                .orElse("identity").toLowerCase();
        return switch (encoding) {
            case "gzip", "x-gzip" -> inflateGzip(bytes);
            case "deflate" -> inflateDeflate(bytes);
            default -> bytes;
        };
    }

    private byte[] inflateGzip(byte[] bytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("StooqHttpClient: dekompresja gzip nie powiodła się ({}), zwracam surowe bajty",
                    e.getMessage());
            return bytes;
        }
    }

    /**
     * HTTP "deflate" jest historycznie dwuznaczne (RFC 1950 zlib vs raw deflate).
     * Stooq wysyła raw bez headera, więc próbujemy zlib, potem raw.
     */
    private byte[] inflateDeflate(byte[] bytes) {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        } catch (IOException zlibError) {
            try (InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(bytes), new Inflater(true))) {
                return in.readAllBytes();
            } catch (IOException rawError) {
                log.warn("StooqHttpClient: dekompresja deflate nieudana (zlib: {}, raw: {}), zwracam surowe bajty",
                        zlibError.getMessage(), rawError.getMessage());
                return bytes;
            }
        }
    }

    private SSLContext insecureSslContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("Nie udało się zbudować insecure SSL context", e);
        }
    }
}
