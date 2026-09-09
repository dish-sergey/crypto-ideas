package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Сводный бот: один экран на всех исполнителей.
 *
 * <h2>Зачем отдельный процесс</h2>
 *
 * С шестью ботами обход каждого по очереди перестал быть практичным. Свести их
 * в один вывод внутри любого из шести нельзя: каждый исполнитель знает только
 * свой журнал и свою пару, а лезть в чужие журналы боту, который в это время
 * торгует, — значит добавить ему блокировок на горячем пути ради отчёта.
 *
 * ⚠️ <b>Токен обязан быть СВОЙ.</b> Два потребителя {@code getUpdates} на одном
 * токене дают 409 и молча отбирают управление друг у друга — это уже ловили на
 * S5. Поэтому седьмой бот Telegram, а не команда в существующем.
 *
 * <h2>Только чтение</h2>
 *
 * Журналы открываются через {@link ExecJournal#readOnly}: ни DDL, ни PRAGMA, ни
 * единой записи. Сводный бот ничего не может испортить по построению — он не
 * умеет ни включать котирование, ни трогать заявки. Управление осталось у
 * каждого исполнителя, и это намеренно: команда «стоп», отданная не тому боту,
 * — самая дорогая ошибка интерфейса, какую тут можно совершить.
 */
public final class InfoBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(InfoBot.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Один наблюдаемый исполнитель: метка, пара, путь к его журналу. */
    public record Watched(String botId, String symbol, String journalPath) {
        public String base() {
            return symbol.substring(0, symbol.indexOf('/'));
        }
    }

    private final String token;
    private final long chatId;
    private final List<Watched> watched;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private volatile boolean alive = true;
    private long offset;

    public InfoBot(String token, long chatId, List<Watched> watched) {
        this.token = token;
        this.chatId = chatId;
        this.watched = watched;
    }

    /**
     * Разбор {@code revx.info.bots}: {@code метка:пара:путь} через запятую.
     *
     * Список в конфиге, а не выведен из каталогов: бот, у которого журнал есть,
     * а сервиса нет, должен быть виден как «молчит», и наоборот. Автоопределение
     * по файлам скрыло бы ровно тот случай, ради которого сводка и заводится.
     */
    public static List<Watched> parse(List<String> spec) {
        List<Watched> out = new ArrayList<>();
        for (String s : spec) {
            String[] p = s.trim().split(":");
            if (p.length != 3) {
                log.error("не разобрал описание бота «{}»: нужно метка:пара:путь", s);
                continue;
            }
            out.add(new Watched(p[0].trim(), p[1].trim(), p[2].trim()));
        }
        return out;
    }

    public static InfoBot fromEnvironment(List<Watched> watched) {
        String token = System.getenv("REVX_INFO_TOKEN");
        String chat = System.getenv("REVX_INFO_CHAT");
        if (token == null || token.isBlank() || chat == null || chat.isBlank()) {
            log.error("нет REVX_INFO_TOKEN/REVX_INFO_CHAT — сводному боту нечем говорить");
            return null;
        }
        return new InfoBot(token.trim(), Long.parseLong(chat.trim()), watched);
    }

    public void stop() {
        alive = false;
    }

    @Override
    public void run() {
        registerCommands();
        dropBacklog();
        send("Сводка запущена. Наблюдаю " + watched.size() + " исполнителей.\n"
                + "/all — состояние всех, /pnl — доход за сутки и неделю.");
        while (alive) {
            try {
                String body = call("getUpdates?timeout=25&offset=" + offset, null);
                if (body == null) {
                    Thread.sleep(2000);
                    continue;
                }
                for (JsonNode update : JSON.readTree(body).path("result")) {
                    offset = update.path("update_id").asLong() + 1;
                    String text = update.path("message").path("text").asText("").trim();
                    long from = update.path("message").path("chat").path("id").asLong();
                    if (from != chatId || text.isEmpty()) {
                        continue;               // чужой чат — молча мимо
                    }
                    handle(text.split("\\s+")[0].toLowerCase(Locale.ROOT));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("сводка: цикл опроса — {}", e.toString());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handle(String command) {
        switch (command) {
            case "/all", "/status", "/start" -> send(all());
            case "/pnl" -> send(pnl());
            case "/help" -> send("""
                    Сводка по всем исполнителям. Только смотрит, ничего не меняет.

                    /all — котирование, инвентарь, сделки и доход за сутки
                    /pnl — доход за 24 часа и за 7 суток, плюс нереализованное

                    Управление осталось у каждого бота: включить, остановить или
                    снять заявки можно только в его собственном чате. Это
                    намеренно — команда, отданная не тому боту, дороже всего.""");
            default -> { }                      // чужие команды не наши
        }
    }

    /** Состояние одного исполнителя, собранное из его журнала. */
    private record Snapshot(String botId, String symbol, boolean quoting, boolean trading,
                            String pausedReason, long parks1h, long lastEventMs,
                            double position, double fair, int fills24, double realised24,
                            double notional24,
                            long placements24, long cap, String note) {
    }

    private Snapshot read(Watched w) {
        long now = System.currentTimeMillis();
        try (ExecJournal j = ExecJournal.readOnly(w.journalPath())) {
            boolean quoting = j.quotingOn();
            ExecJournal.LastQuote q = j.lastQuote();
            Double pos = j.getState("position");
            FifoLedger ledger = PnlReport.build(j);
            // ⚠️ «Торгует» = котирование включено И гейты разрешают. Это разные
            // вещи: бот с включённым котированием может час стоять в отводе,
            // потому что опорная книга широка, и по сводке выглядеть рабочим.
            boolean trading = quoting && q.quotable();
            return new Snapshot(w.botId(), w.symbol(), quoting, trading,
                    q.reason(), j.parksSince(now - 3_600_000L), q.tsMs(),
                    pos == null ? 0 : pos, j.lastFair(),
                    ledger.tradingClosedSince(now - 86_400_000L),
                    ledger.tradingRealisedSince(now - 86_400_000L),
                    ledger.tradingClosedNotionalSince(now - 86_400_000L),
                    j.placementsSince(now - 86_400_000L),
                    ExecLimits.maxPlacementsPerDay(w.botId()), null);
        } catch (Exception e) {
            // Недоступный журнал — это САМ ПО СЕБЕ результат: бот не запускался
            // или упал так, что файла нет. Молчать об этом нельзя.
            return new Snapshot(w.botId(), w.symbol(), false, false, null, 0, 0, 0, 0, 0, 0, 0, 0,
                    ExecLimits.maxPlacementsPerDay(w.botId()), "журнал недоступен");
        }
    }

    private String all() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("СВОДКА ПО ИСПОЛНИТЕЛЯМ\n\n");
        double totalRealised = 0;
        double totalInventory = 0;
        long totalPlacements = 0;
        long totalCap = 0;
        for (Watched w : watched) {
            Snapshot s = read(w);
            totalRealised += s.realised24();
            // Инвентарь остановленного бота — снимок; в сумму не берём, см. ниже.
            if (s.quoting() || now - s.lastEventMs() <= 5 * 60_000L) {
                totalInventory += s.position() * s.fair();
            }
            totalPlacements += s.placements24();
            totalCap += s.cap();
            String age = s.lastEventMs() > 0
                    ? ago(System.currentTimeMillis() - s.lastEventMs()) : "нет тиков";
            // ⚠️ Три состояния, а не два. ⚪ выключен — так и задумано. 🟢 торгует.
            // 🟡 включён, но НЕ торгует: гейт закрыт, заявки в отводе. Именно это
            // состояние прежде выглядело рабочим и стоило боту E трёх четвертей
            // суточного бюджета постановок за час.
            String mark = !s.quoting() ? "⚪" : s.trading() ? "🟢" : "🟡";
            String what = !s.quoting() ? "выключен"
                    : s.trading() ? "торгует"
                    : "НЕ ТОРГУЕТ: " + (s.pausedReason() == null ? "гейт закрыт" : s.pausedReason());
            // Доля бюджета важнее самого числа: у ботов разные потолки, и «60»
            // у одного благополучно, а у другого три четверти суток.
            // ⚠️ У ОСТАНОВЛЕННОГО БОТА ИНВЕНТАРЬ — ЭТО СТАРЫЙ СНИМОК, а не факт.
            //
            // Сводка собирается ТОЛЬКО из журналов: ключа у неё нет и быть не
            // должно. Пока бот работает, он сам пишет позицию каждую секунду;
            // как только его остановили, запись замирает, а монеты продолжают
            // жить своей жизнью. 09.09.2026 боты E и F показывали $13.5 и $11.3
            // инвентаря через два часа после того, как он был продан вручную:
            // заявки исполнились, а сообщить об этом было некому.
            //
            // Поэтому у остановленного бота позиция помечается снимком и НЕ
            // попадает в итоговую сумму: лучше не показать, чем показать неправду.
            boolean stale = !s.quoting() && now - s.lastEventMs() > 5 * 60_000L;
            long pct = s.cap() > 0 ? 100 * s.placements24() / s.cap() : 0;
            sb.append(String.format(Locale.ROOT,
                    "%s %s  %s — %s%n  закрытых пар 24ч %d, доход %+.4f USDC%n"
                            + "  инвентарь %.2f USDC%s, постановок %d из %d (%d%%)%n"
                            + "  оборот 24ч %.2f USDC, доход %+.1f б.п. оборота%n"
                            + "  отводов за час %d, тик %s%s%n%n",
                    mark, s.botId().toUpperCase(Locale.ROOT), s.symbol(), what,
                    s.fills24(), s.realised24(), s.position() * s.fair(),
                    stale ? " — СНИМОК на момент остановки, НЕ ПРОВЕРЕНО" : "",
                    s.placements24(), s.cap(), pct,
                    s.notional24(),
                    s.notional24() > 0 ? s.realised24() / s.notional24() * 10_000 : 0,
                    s.parks1h(), age,
                    s.note() == null ? "" : "\n  ⚠️ " + s.note()));
        }
        sb.append(String.format(Locale.ROOT,
                "ИТОГО за 24 ч: %+.4f USDC%n"
                        + "инвентарь %.2f USDC (только работающие), "
                        + "постановок %d из %d (аккаунту дают 1000)",
                totalRealised, totalInventory, totalPlacements, totalCap));
        return sb.toString();
    }

    private String pnl() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("ДОХОД ПО ИСПОЛНИТЕЛЯМ\n\n");
        sb.append(String.format(Locale.ROOT, "%-4s %-10s %10s %10s %10s%n",
                "бот", "пара", "24 часа", "7 суток", "в позиции"));
        double d1 = 0;
        double d7 = 0;
        double unreal = 0;
        for (Watched w : watched) {
            try (ExecJournal j = ExecJournal.readOnly(w.journalPath())) {
                FifoLedger ledger = PnlReport.build(j);
                double a = ledger.tradingRealisedSince(now - 86_400_000L);
                double b = ledger.tradingRealisedSince(now - 7 * 86_400_000L);
                double fair = j.lastFair();
                double u = ledger.position().unrealised(fair);
                d1 += a;
                d7 += b;
                unreal += u;
                sb.append(String.format(Locale.ROOT, "%-4s %-10s %+10.4f %+10.4f %+10.4f%n",
                        w.botId().toUpperCase(Locale.ROOT), w.base(), a, b, u));
            } catch (Exception e) {
                sb.append(String.format(Locale.ROOT, "%-4s %-10s  журнал недоступен%n",
                        w.botId().toUpperCase(Locale.ROOT), w.base()));
            }
        }
        sb.append(String.format(Locale.ROOT, "%-4s %-10s %+10.4f %+10.4f %+10.4f%n",
                "ВСЕ", "", d1, d7, unreal));
        // ⚠️ Нереализованное отделено намеренно. «Реализовано» — это закрытые
        // пары FIFO; непроданный остаток в него не входит, и бот, который только
        // покупал, показывает ровно ноль дохода при потраченных деньгах.
        sb.append("\n«В позиции» — переоценка непроданного остатка по последней "
                + "справедливой цене. В «доход» она не входит.");
        return sb.toString();
    }

    private static String ago(long ms) {
        if (ms < 60_000) {
            return ms / 1000 + " с назад";
        }
        if (ms < 3_600_000) {
            return ms / 60_000 + " мин назад";
        }
        return ms / 3_600_000 + " ч назад";
    }

    private void registerCommands() {
        try {
            String response = call("setMyCommands", "commands=" + URLEncoder.encode("""
                    [{"command":"all","description":"состояние всех исполнителей"},
                     {"command":"pnl","description":"доход за 24 часа и 7 суток"},
                     {"command":"help","description":"что тут есть"}]"""
                    .replaceAll("\\s*\\n\\s*", ""), StandardCharsets.UTF_8));
            // Ответ обязателен к проверке: Telegram отвечает 200 и телом
            // {"ok":false,...}, исключения не будет. На исполнителях это уже
            // однажды скрыло пустое меню целиком.
            if (response == null || !response.contains("\"ok\":true")) {
                log.error("МЕНЮ НЕ ЗАРЕГИСТРИРОВАНО, Telegram ответил: {}", response);
            }
        } catch (Exception e) {
            log.warn("меню команд: {}", e.toString());
        }
    }

    /**
     * Выбросить накопленное за время простоя.
     *
     * Иначе после перезапуска бот отвечает на команды, отданные когда его не
     * было, — для сводки это безобидно, но путает: приходит стопка одинаковых
     * таблиц неизвестной давности.
     */
    private void dropBacklog() {
        try {
            String body = call("getUpdates?timeout=0&offset=-1", null);
            if (body == null) {
                return;
            }
            for (JsonNode u : JSON.readTree(body).path("result")) {
                offset = u.path("update_id").asLong() + 1;
            }
            if (offset > 0) {
                call("getUpdates?timeout=0&offset=" + offset, null);
            }
        } catch (Exception e) {
            log.warn("не выбросил накопленное: {}", e.toString());
        }
    }

    public void send(String text) {
        try {
            call("sendMessage", "chat_id=" + chatId + "&text="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("не отправилось сообщение: {}", e.toString());
        }
    }

    private String call(String method, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(40));
        if (form == null) {
            b.GET();
        } else {
            b.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() / 100 == 2 ? r.body() : null;
    }
}
