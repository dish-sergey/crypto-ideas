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

/**
 * Управление исполнителем из Telegram.
 *
 * **Бот отдельный, не S5.** Два потребителя {@code getUpdates} на одном токене
 * дают 409, и живой торговый слушатель S5 сломается. Токен и chat_id приходят
 * из переменных окружения.
 *
 * Четыре правила, без которых бот опаснее пользы:
 *
 * 1. **Он не меняет параметров.** Только стоп, старт и показ состояния. Ни
 *    размера, ни отступа, ни пределов — всё это в конфиге и в коде. Иначе доступ
 *    к чату превращается в доступ к деньгам.
 * 2. **Слушатель на своём потоке.** {@code /panic} обязана срабатывать, даже
 *    если цикл котирования заклинил; будь listener внутри цикла, аварийная
 *    остановка существовала бы только на бумаге.
 * 3. **Чужой чат игнорируется молча.** Никаких ответов и подсказок тому, кто
 *    не является владельцем.
 * 4. **Котирование стартует выключенным** — это правило живёт в {@link QuoteLoop},
 *    а бот лишь не даёт себе включить его самостоятельно при запуске.
 */
public final class ExecBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ExecBot.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String token;
    private final long chatId;
    private final QuoteLoop loop;
    private final ExecJournal journal;
    private final Runnable panic;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private volatile boolean alive = true;
    private long offset;

    public ExecBot(String token, long chatId, QuoteLoop loop, ExecJournal journal, Runnable panic) {
        this.token = token;
        this.chatId = chatId;
        this.loop = loop;
        this.journal = journal;
        this.panic = panic;
    }

    public static ExecBot fromEnvironment(QuoteLoop loop, ExecJournal journal, Runnable panic) {
        String token = System.getenv("REVX_BOT_TOKEN");
        String chat = System.getenv("REVX_BOT_CHAT");
        if (token == null || token.isBlank() || chat == null || chat.isBlank()) {
            log.warn("нет REVX_BOT_TOKEN/REVX_BOT_CHAT — исполнитель без управления из Telegram");
            return null;
        }
        return new ExecBot(token.trim(), Long.parseLong(chat.trim()), loop, journal, panic);
    }

    public void stop() {
        alive = false;
    }

    @Override
    public void run() {
        registerCommands();
        dropBacklog();
        send("Исполнитель запущен. Котирование ВЫКЛЮЧЕНО — включить: /start\n\n"
                + loop.profile() + "\n" + ExecLimits.describe(loop.botId()));
        while (alive) {
            try {
                String body = call("getUpdates?timeout=25&offset=" + offset, null);
                JsonNode root = JSON.readTree(body);
                for (JsonNode update : root.path("result")) {
                    offset = update.path("update_id").asLong() + 1;
                    handle(update.path("message"));
                }
            } catch (Exception e) {
                log.warn("опрос Telegram упал: {}", e.toString());
                sleep(5_000);
            }
        }
    }

    /**
     * Выбросить накопившиеся сообщения, НЕ выполняя их.
     *
     * ⚠️ Telegram держит непрочитанные обновления в очереди и отдаёт их первым же
     * {@code getUpdates}. Без этого команда, отправленная когда процесса не было,
     * выполняется при следующем запуске — и 01.09.2026 именно так и вышло: бот B
     * включил котирование через СЕКУНДУ после старта, подобрав {@code /start},
     * посланный за восемь минут до его первого запуска.
     *
     * Это прямое нарушение правила 1 ({@link QuoteLoop}): «стартует остановленным,
     * перезапуск после падения не должен сам возобновлять торговлю». Команда
     * включить торговлю обязана быть НАМЕРЕНИЕМ ЧЕЛОВЕКА В ЭТОТ МОМЕНТ, а не
     * эхом из прошлого — тем более что упавший бот перезапускается как раз тогда,
     * когда в чате уже лежат встревоженные команды.
     */
    private void dropBacklog() {
        try {
            String body = call("getUpdates?timeout=0&offset=-1", null);
            JsonNode result = JSON.readTree(body).path("result");
            int dropped = 0;
            for (JsonNode update : result) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                dropped++;
            }
            if (dropped > 0) {
                // Один запрос отдаёт только хвост очереди; сдвинутый offset
                // подтверждает приём всего, что было до него.
                call("getUpdates?timeout=0&offset=" + offset, null);
                log.warn("отброшено накопившихся сообщений Telegram: {} — команды из "
                        + "прошлого не выполняются", dropped);
                journal.event("telegram_backlog", "отброшено " + dropped);
            }
        } catch (Exception e) {
            // Не смогли — тем более не выполняем: лучше пропустить команду, чем
            // включить торговлю по чужому эху.
            log.warn("не удалось очистить очередь Telegram: {}", e.toString());
        }
    }

    private void handle(JsonNode message) {
        if (message.path("chat").path("id").asLong() != chatId) {
            return;                       // чужой чат — молча
        }
        String text = message.path("text").asText("").trim();
        journal.event("telegram_in", text);
        switch (text.split("\\s+")[0]) {
            case "/start" -> {
                // Деньги бот берёт САМ: в этом решения нет, оно механическое —
                // сколько стоит недостающая до потолка часть инвентаря, столько
                // и нужно. Решение есть только в захвате ЛОТОВ (/claim N), а
                // предпросмотр даёт /free.
                String taken = loop.topUpCash();
                // Бот без своей доли кассы встаёт на отказах «Insufficient
                // balance» и жжёт на них общий суточный лимит постановок —
                // у бота B таких отказов было 197 за сутки. Лучше не пустить.
                String blocked = loop.cannotStart();
                if (blocked != null) {
                    send("НЕ включено. " + blocked
                            + (taken == null ? "" : "\n" + taken));
                } else {
                    loop.startQuoting();
                    send("Котирование ВКЛЮЧЕНО."
                            + (taken == null ? "" : "\n" + taken) + "\n" + status());
                }
            }
            case "/stop" -> {
                loop.stopQuoting();
                send("Котирование выключено, заявки сняты.\n" + status());
            }
            case "/panic" -> {
                send("ПАНИКА: снимаю все заявки и выхожу.");
                journal.event("telegram_panic", "команда из чата");
                panic.run();
            }
            case "/status" -> send(status());
            case "/stats" -> send(stats());
            case "/limits" -> send(ExecLimits.describe(loop.botId()));
            case "/free" -> send(loop.describeFree());
            case "/claim" -> {
                String[] parts = text.split("\s+");
                send(parts.length < 2 ? "Сколько лотов? Например: /claim 8"
                        : loop.claimLots(parseLots(parts[1])));
            }
            case "/release" -> send(loop.release());
            case "/pnl" -> send(PnlReport.render(journal, loop.stats().lastFair(),
                    loop.lotSize(), loop.base(), loop.stats().inventory()));
            case "/help" -> send(help());
            default -> { }                // неизвестная команда — без ответа
        }
    }

    private String status() {
        QuoteLoop.Stats s = loop.stats();
        return loop.profile() + """
                Состояние: %s%s
                Справедливая цена: %.2f
                Инвентарь: %.8f (%.0f%% потолка, %.1f лота)
                Постановок: %d, замен: %d, отмен: %d""".formatted(
                s.state(),
                s.pausedReason() == null ? "" : " (" + s.pausedReason() + ")",
                s.lastFair(), s.inventory(),
                loop.inventoryCap() > 0 ? 100 * s.inventory() / loop.inventoryCap() : 0,
                loop.lotSize() > 0 ? s.inventory() / loop.lotSize() : 0,
                s.placements(), s.replaces(), s.cancels());
    }

    /**
     * Сверка с предсказанием — то, ради чего этап и затевался. Числа модели:
     * 104 исполнения в сутки и 6.0% с отрицательным захватом (док. 91 §3).
     */
    private String stats() {
        QuoteLoop.Stats s = loop.stats();
        String head = loop.profile() + "\n";
        double cap = loop.inventoryCap();
        double lot = loop.lotSize();
        int limit = ExecLimits.maxPlacementsPerDay(loop.botId());
        // ⚠️ Постановки берутся из ЖУРНАЛА за скользящие 24 часа — тем же
        // способом, каким считает предел. Раньше здесь стоял счётчик в памяти,
        // и он обнулялся при каждом запуске: 04.09.2026 бот показывал
        // «0 из 400» и в ту же секунду блокировался на 407 из 400.
        long used = loop.placementsLastDay();
        return head + """
                Исполнений: %d
                Постановок за 24 ч: %d из %d (осталось %d)
                Замен: %d
                Инвентарь: %.8f = %.1f лота (%.0f%% потолка)
                Цель скоса: %.0f%% потолка = %.1f лота
                Записей в журнале: %d""".formatted(
                s.fills(), used, limit, Math.max(0, limit - used),
                s.replaces(), s.inventory(),
                lot > 0 ? s.inventory() / lot : 0,
                cap > 0 ? 100 * s.inventory() / cap : 0,
                loop.skewTarget() * 100,
                lot > 0 ? loop.skewTarget() * cap / lot : 0,
                journal.countRequests());
    }

    /** Лоты человеку удобнее монет, но опечатку на порядок ловить всё равно надо. */
    private static double parseLots(String s) {
        try {
            return Double.parseDouble(s.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String help() {
        return """
                /status — состояние и текущие котировки
                /stats  — исполнения против предсказания модели
                /pnl    — реализовано и нереализовано по FIFO, окна 3ч…7дн
                /start  — включить котирование
                /stop   — выключить и снять заявки
                /panic  — снять всё и выйти из процесса
                /limits — жёсткие пределы (только показ)
                /free   — сколько инвентаря свободно и кто чем владеет
                /claim N — взять N свободных ЛОТОВ (только пока не котирует).
                           Деньги брать не нужно: /start берёт их сам
                /release — отдать свой инвентарь и кассу в общий котёл
                /help   — это сообщение

                Порядок при запуске: /free (посмотреть) → /claim N (взять лоты)
                → /start (сам возьмёт деньги и включит).
                Параметры котирования бот не меняет: они в конфиге и в коде.""";
    }

    public void send(String text) {
        try {
            call("sendMessage", "chat_id=" + chatId + "&text="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("не отправилось сообщение: {}", e.toString());
        }
    }

    /**
     * Меню команд в Telegram.
     *
     * ⚠️ Список ЗДЕСЬ — отдельный от обработчика и от {@link #help()}, и три
     * места приходится держать в согласии руками. 04.09.2026 добавили `/free`,
     * `/claim`, `/release` и `/pnl` в обработчик, а меню не тронули — в клиенте
     * их не стало видно вовсе. Добавляя команду, правь все три.
     */
    private void registerCommands() {
        try {
            String response = call("setMyCommands", "commands=" + URLEncoder.encode("""
                    [{"command":"status","description":"состояние и инвентарь в % потолка"},
                     {"command":"pnl","description":"реализовано и нереализовано, окна 3ч-7дн"},
                     {"command":"free","description":"кто чем владеет и сколько свободно"},
                     {"command":"claim","description":"взять N свободных лотов"},
                     {"command":"release","description":"отдать свой инвентарь и кассу"},
                     {"command":"stats","description":"исполнения против предсказания"},
                     {"command":"start","description":"взять деньги и включить котирование"},
                     {"command":"stop","description":"выключить и снять заявки"},
                     {"command":"panic","description":"снять всё и выйти"},
                     {"command":"limits","description":"жёсткие пределы"}]"""
                    .replaceAll("\\s*\\n\\s*", ""), StandardCharsets.UTF_8));
            // ⚠️ Ответ обязателен к проверке. Telegram отвечает 200 и телом
            // {"ok":false,...} — исключения не будет, и молчаливое выбрасывание
            // ответа скрывало поломку регистрации ЦЕЛИКОМ: 04.09.2026 меню бота
            // было пустым (0 команд), а в логе ни строчки. Узнали от человека.
            if (response == null || !response.contains("\"ok\":true")) {
                log.error("МЕНЮ КОМАНД НЕ ЗАРЕГИСТРИРОВАНО, Telegram ответил: {}", response);
            } else {
                log.info("меню команд зарегистрировано");
            }
        } catch (Exception e) {
            log.warn("не зарегистрировались команды: {}", e.toString());
        }
    }

    private String call(String method, String form) throws Exception {
        URI uri = URI.create("https://api.telegram.org/bot" + token + "/" + method);
        HttpRequest request = form == null
                ? HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().build()
                : HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
