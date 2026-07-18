package org.home.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Слой данных торговой системы (этап 0 дорожной карты, док. 00 §6).
 * Коллекторы бесплатных источников по док. 09: расписание либо разовые
 * команды backfill/collect через аргументы CLI (см. {@link org.home.data.cli.CliRunner}).
 */
@SpringBootApplication
@EnableScheduling
public class CryptoDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoDataApplication.class, args);
    }
}
