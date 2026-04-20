package pl.rafal.stockpicker.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Konfiguracja cache'a dla aplikacji.
 *
 * Dlaczego cache'ujemy i jak dobrałem TTL?
 *
 * Stooq aktualizuje dane raz dziennie po zamknięciu sesji GPW (około 17:30).
 * Na interwale tygodniowym najnowsza świeca zamyka się w piątek po 17:30,
 * więc w skali tygodnia mamy tylko JEDNĄ realną aktualizację danych.
 *
 * Ustawienie TTL na 4 godziny oznacza że:
 *   - W ciągu dnia giełdowego użytkownik uruchamiający screening 3-4 razy
 *     uderzy do stooq maksymalnie raz (pierwszy strzał), a kolejne wezmą
 *     z cache'a
 *   - Po zamknięciu sesji pierwsze uruchomienie następnego dnia dostanie
 *     świeże dane (cache już wygasł przez noc)
 *
 * Rozmiar cache'a: 1000 wpisów to z dużym zapasem pokrywa ~300 spółek
 * (każda spółka może być w cache pod kilkoma kluczami zależnie od parametru
 * weeksBack). Caffeine używa polityki Window TinyLFU która jest najbardziej
 * efektywna statystycznie - utrzyma w pamięci najczęściej odpytywane spółki.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        // weeklyCandles - historyczne notowania per ticker (jeden klucz na ticker).
        // wig20Composition - lista 20 spółek WIG20 ze stopami zwrotu (jeden klucz "wig20").
        // Wspólne TTL 4h: po sesji GPW (17:30) cache wygasa do następnego dnia.
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "weeklyCandles", "wig20Composition");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(4, TimeUnit.HOURS)
                .maximumSize(1000)
                .recordStats()
        );
        return cacheManager;
    }
}
