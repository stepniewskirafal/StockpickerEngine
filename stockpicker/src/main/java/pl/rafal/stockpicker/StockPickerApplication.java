package pl.rafal.stockpicker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punkt startowy aplikacji Stock Picker Engine 80.40.
 *
 * Adnotacja @SpringBootApplication to w rzeczywistości skrót dla trzech:
 *   @Configuration - informuje że klasa zawiera definicje beanów
 *   @EnableAutoConfiguration - włącza auto-konfigurację Spring Boot
 *   @ComponentScan - skanuje pakiet i podpakiety w poszukiwaniu komponentów
 *
 * Uruchomienie:
 *   mvn spring-boot:run
 *
 * Następnie otwórz http://localhost:8080 w przeglądarce.
 */
@SpringBootApplication
public class StockPickerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockPickerApplication.class, args);
    }
}
