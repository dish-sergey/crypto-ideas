package org.home.data.trade;

/**
 * Разовый показ полного жизненного цикла сделки S5 в Telegram (без реальных ордеров и без мока — просто
 * последовательность сообщений с паузами). Чтобы оператор увидел, как выглядит каждый шаг. Только отправка
 * (sendMessage) — с работающим на микро слушателем не конфликтует.
 */
public class S5Demo {

    public static void run(String configPath) throws Exception {
        S5TelegramConfig tg = S5TelegramConfig.load(configPath);
        Notifier n = new TelegramNotifier(new HttpTelegramTransport(tg.token()), tg.chatId());

        n.push(Alert.info("🎬 Демо S5 — жизненный цикл сделки",
                "Покажу по шагам, как это работает. Это ДЕМО: реальных ордеров нет."));
        sleep(3);
        n.push(Alert.info("① За ~2 дня до дня входа (≈ −7 дней до разлока)",
                "PFDEMOUSD: 8.0% team circ — вход через 2 дн.\nВ реале под сообщением — кнопки ✅ Подтвердить / ✖️ Отклонить."));
        sleep(4);
        n.push(Alert.warn("② Эскалация напоминаний (пока не подтвердил)",
                "≈24ч → ≈12ч → ≈3ч → ≈1ч до входа, каждое один раз. Подтвердил — напоминания прекращаются."));
        sleep(4);
        n.push(Alert.info("③ Подтвердил → в ДЕНЬ ВХОДА (−5 дней) ставится ордер",
                "Открыт шорт PFDEMOUSD qty=0.4500 @ 10.00\nРазмер = 4.5% баланса фьючерс-аккаунта. Открытие — не по факту подтверждения, а строго на −5 день."));
        sleep(4);
        n.push(Alert.info("④ Держим 5 дней → плановый выход в день разлока",
                "PFDEMOUSD +6.2% — закрыто по плану (шорт в плюсе: рынок просел к разлоку)."));
        sleep(4);
        n.push(Alert.warn("⑤ Защита: стоп −30%",
                "Если цена пойдёт против нас и вырастет на 30% внутри дня — авто-закрытие с −30% (не доводим до ликвидации)."));
        sleep(3);
        n.push(Alert.info("Конец демо",
                "Так выглядит один полный цикл. Реальные сделки — только после пополнения счёта и твоего явного «поехали»."));
    }

    private static void sleep(int sec) {
        try { Thread.sleep(sec * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
