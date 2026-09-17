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

    /**
     * Метки ботов, скрытых из {@code /all} и {@code /pnl}.
     *
     * <h2>Почему в памяти, а не в файле</h2>
     *
     * Сводный бот не пишет НИЧЕГО — в этом весь его смысл: испортить он не может
     * по построению. Ради удобства отображения заводить ему первый в жизни путь
     * записи было бы плохой сделкой. Перезапуск возвращает всех — и это скорее
     * хорошо: после рестарта видно полную картину, а не то, что кто-то однажды
     * убрал с глаз.
     *
     * <h2>Скрытый бот не перестаёт существовать</h2>
     *
     * ⚠️ Скрытие меняет только показ. Итоги считаются по ВИДИМЫМ (иначе сумма не
     * сходилась бы со строками), но снизу всегда написано, кто скрыт, и если
     * скрытый бот КОТИРУЕТ или держит инвентарь — об этом говорится отдельной
     * строкой. Иначе «убрал с глаз» однажды означало бы «не заметил, что он
     * торгует».
     */
    private final java.util.Set<String> hidden =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Путь к общему реестру резерваций.
     *
     * ⚠️ Читается ТОЛЬКО на чтение и только с этой же машины: сводка не ходит на
     * площадку и ключа не имеет — см. правило в CLAUDE.md.
     */
    private final String allocPath;

    /**
     * База сборщика — ТОЛЬКО ради цен, и только на чтение.
     *
     * Сводке нужен курс монеты в USDC, чтобы «ничейное» можно было прочесть
     * деньгами, а не количеством: 7250 PEPE и 0.0472 SOL — числа несравнимые,
     * пока не переведены в одну единицу. Своего источника цены у сводки нет и
     * быть не должно: ключа она не имеет по построению.
     */
    private final String bookPath;

    /**
     * ⚠️ СКОЛЬКО МОЛЧАНИЯ ЗНАЧИТ «ПОТОК КОТИРОВАНИЯ МЁРТВ» (задача A64).
     *
     * Тик пишется примерно раз в секунду и пишется ВО ВСЕХ известных паузах:
     * и когда закрыт гейт по опоре, и при отводе на затыке площадки
     * ({@code venue_stall}), и при заморозке резерва — там меняется только
     * {@code reason}, а запись остаётся. Поэтому у ВКЛЮЧЁННОГО бота молчание
     * дольше нескольких минут не бывает штатным: это мёртвый поток.
     *
     * Три минуты, а не одна: перезапуск процесса гасит котирование
     * ({@code boot} считается выключением), значит пауза на выкатку под порог не
     * попадает вовсе, и запас нужен только на редкий долгий цикл опроса.
     */
    static final long SILENCE_MS = 3 * 60_000L;

    /** Кому уже сказали про молчание и когда — чтобы не повторять каждый цикл. */
    private final java.util.Map<String, Long> silenceTold = new java.util.HashMap<>();

    public InfoBot(String token, long chatId, List<Watched> watched) {
        this(token, chatId, watched,
                System.getProperty("revx.info.alloc", "../revx-shared/alloc.db"));
    }

    public InfoBot(String token, long chatId, List<Watched> watched, String allocPath) {
        this.token = token;
        this.chatId = chatId;
        this.watched = watched;
        this.allocPath = allocPath;
        this.bookPath = System.getProperty("revx.info.book", "../revx/data/revx.db");
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
                + "/all — состояние всех, /pnl — доход за сутки и неделю.\n"
                + "/alloc — кто что держит за собой и сколько ничейного.\n"
                + "/hide d e f — убрать лишних с глаз, /show all — вернуть.");
        while (alive) {
            try {
                String body = call("getUpdates?timeout=25&offset=" + offset, null);
                if (body == null) {
                    Thread.sleep(2000);
                    continue;
                }
                watchSilence();
                for (JsonNode update : JSON.readTree(body).path("result")) {
                    offset = update.path("update_id").asLong() + 1;
                    String text = update.path("message").path("text").asText("").trim();
                    long from = update.path("message").path("chat").path("id").asLong();
                    if (from != chatId || text.isEmpty()) {
                        continue;               // чужой чат — молча мимо
                    }
                    handle(text);
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

    private void handle(String text) {
        String[] parts = text.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        switch (command) {
            case "/all", "/status", "/start" -> send(all());
            case "/pnl" -> send(pnl());
            case "/alloc", "/claims" -> send(alloc());
            case "/hide" -> send(visibility(args, true));
            case "/show" -> send(visibility(args, false));
            case "/help" -> send("""
                    Сводка по всем исполнителям. Только смотрит, ничего не меняет.

                    /all — котирование, форма сетки, инвентарь, сделки и доход
                    /pnl — доход за 24 часа и за 7 суток, плюс нереализованное
                    /alloc — кто что держит за собой и сколько ничейного

                    💀 МОЛЧИТ — котирование числится включённым, а тиков нет:
                    поток котирования мёртв, systemd этого НЕ видит. Про это
                    сводка сообщает сама, не дожидаясь вопроса.

                    Ничейное — монета, не записанная ни за одним ботом: ею никто
                    не торгует, пока кто-нибудь не сделает /claim в своём чате.
                    Строкой оно показывается и в конце /all.
                    /hide d e f — убрать ботов из /all и /pnl
                    /show d — вернуть, /show all — вернуть всех
                    /hide и /show без меток — кто сейчас показан

                    Скрытие живёт до перезапуска сводки и меняет только показ.
                    Скрытый бот, который котирует или держит инвентарь, всё равно
                    называется отдельной строкой — «убрал с глаз» не должно
                    однажды означать «не заметил, что он торгует».

                    Управление осталось у каждого бота: включить, остановить или
                    снять заявки можно только в его собственном чате. Это
                    намеренно — команда, отданная не тому боту, дороже всего.""");
            default -> { }                      // чужие команды не наши
        }
    }

    /**
     * {@code /hide метки…} и {@code /show метки…}: кого показывать в сводках.
     *
     * Две команды вместо одной переключающей — намеренно. Переключатель заставляет
     * помнить текущее состояние («я его сейчас спрятал или вернул?»), а здесь
     * команда сама говорит, что произойдёт. Без меток обе просто показывают
     * расклад.
     */
    String visibility(String[] args, boolean hide) {   // пакетная видимость: тест
        if (args.length == 0) {
            return state();
        }
        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        for (Watched w : watched) {
            known.add(w.botId().toLowerCase(Locale.ROOT));
        }
        if (!hide && args.length == 1 && "all".equalsIgnoreCase(args[0])) {
            hidden.clear();
            return "Показаны все.\n\n" + state();
        }
        List<String> unknown = new ArrayList<>();
        java.util.Set<String> ask = new java.util.LinkedHashSet<>();
        for (String a : args) {
            String id = a.toLowerCase(Locale.ROOT);
            if (known.contains(id)) {
                ask.add(id);
            } else {
                unknown.add(a);
            }
        }
        if (!unknown.isEmpty()) {
            return "Не знаю таких: " + String.join(", ", unknown)
                    + "\nЕсть: " + String.join(", ", known);
        }
        // ⚠️ Спрятать всех нельзя. Пустая сводка — не настройка, а поломка
        // прибора: она выглядит как «всё тихо» при любом происходящем.
        if (hide) {
            java.util.Set<String> after = new java.util.LinkedHashSet<>(hidden);
            after.addAll(ask);
            if (after.size() >= known.size()) {
                return "Так скроются все, и сводка перестанет что-либо показывать.\n"
                        + "Оставьте хотя бы одного.\n\n" + state();
            }
        }
        if (hide) {
            hidden.addAll(ask);
        } else {
            hidden.removeAll(ask);
        }
        return state();
    }

    /** Кто показан, кто скрыт — одинаково после любой из двух команд. */
    String state() {
        StringBuilder sb = new StringBuilder("Показ в /all и /pnl:\n");
        for (Watched w : watched) {
            boolean off = hidden.contains(w.botId().toLowerCase(Locale.ROOT));
            sb.append(off ? "  ⛔ " : "  ✅ ")
                    .append(w.botId().toUpperCase(Locale.ROOT))
                    .append(' ').append(w.symbol())
                    .append(off ? " — скрыт" : "")
                    .append('\n');
        }
        sb.append("\n/hide метки — убрать, /show метки — вернуть, /show all — вернуть всех.");
        sb.append("\nПерезапуск сводки возвращает всех.");
        return sb.toString();
    }

    /** Наблюдаемые за вычетом скрытых — то, что попадает в сводки. */
    List<Watched> visible() {
        return watched.stream()
                .filter(w -> !hidden.contains(w.botId().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * Приписка о скрытых.
     *
     * Скрытый бот, который котирует или держит монеты, назван отдельно и с
     * предупреждением: сводка нужна как раз для того, чтобы такое не терялось.
     */
    private String hiddenNote() {
        if (hidden.isEmpty()) {
            return "";
        }
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        List<String> alive = new ArrayList<>();
        for (Watched w : watched) {
            if (!hidden.contains(w.botId().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String id = w.botId().toUpperCase(Locale.ROOT);
            names.add(id);
            Snapshot s = read(w);
            boolean fresh = s.quoting() || now - s.lastEventMs() <= 5 * 60_000L;
            boolean carries = fresh && Math.abs(s.position() * s.fair()) > 0.5;
            if (s.quoting() || carries) {
                alive.add(id + " " + w.base() + (s.quoting() ? " КОТИРУЕТ" : "")
                        + (carries ? String.format(Locale.ROOT, " инвентарь %.2f USDC",
                                s.position() * s.fair()) : ""));
            }
        }
        String note = "\n\nСкрыто из показа: " + String.join(", ", names)
                + " (/show all — вернуть)";
        if (!alive.isEmpty()) {
            note += "\n⚠️ и они не спят: " + String.join("; ", alive);
        }
        return note;
    }

    /** Состояние одного исполнителя, собранное из его журнала. */
    /**
     * @param form форма сетки из события {@code boot}: «1 ур.» или «3 ур. × 2.0 б.п.».
     *             ⚠️ С 12.09.2026 три бота из шести работают на трёх уровнях
     *             (опыт A39), и по сводке их было НЕ ОТЛИЧИТЬ от одноуровневых —
     *             а сравниваем мы именно формы. Берётся из журнала, как и всё
     *             остальное здесь: сводка на площадку не ходит.
     */
    private record Snapshot(String botId, String symbol, boolean quoting, boolean trading,
                            String pausedReason, long parks1h, long lastEventMs,
                            double position, double fair, int fills24, double realised24,
                            double notional24,
                            long placements24, long cap, String note, String form) {
    }

    /** Форма сетки бота из последнего {@code boot}; пусто, если настройки не записаны. */
    private static String formOf(ExecJournal journal) {
        try {
            ExecJournal.Boot boot = journal.lastBoot();
            if (boot == null) {
                return "";
            }
            org.home.data.revx.replay.BootParams p =
                    org.home.data.revx.replay.BootParams.parse(boot.detail());
            if (p == null) {
                return "";
            }
            return p.levels() > 1
                    ? String.format(Locale.ROOT, "%d ур. × %.1f б.п.",
                            p.levels(), p.levelStep() * 10_000)
                    : "1 ур.";
        } catch (Exception e) {
            return "";
        }
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
                    ExecLimits.maxPlacementsPerDay(w.botId()), null, formOf(j));
        } catch (Exception e) {
            // Недоступный журнал — это САМ ПО СЕБЕ результат: бот не запускался
            // или упал так, что файла нет. Молчать об этом нельзя.
            return new Snapshot(w.botId(), w.symbol(), false, false, null, 0, 0, 0, 0, 0, 0, 0, 0,
                    ExecLimits.maxPlacementsPerDay(w.botId()), "журнал недоступен", "");
        }
    }

    /**
     * 🔑 СТОРОЖ МОЛЧАНИЯ: единственное, чего не видит ни systemd, ни сводка по запросу.
     *
     * <h2>Зачем</h2>
     *
     * 16.09.2026 пять ботов из шести умерли в течение часа после выкатки:
     * {@code app.jar} перезаписали ПОД работающими процессами, Spring грузит
     * классы лениво, и на первом же исключении, которое надо было залогировать,
     * поток котирования падал с
     * {@code ClassNotFoundException: ch.qos.logback.classic.spi.ThrowableProxy}.
     * Процесс при этом жив — в нём остаётся опрос Telegram, — поэтому
     * {@code systemctl} двадцать три часа показывал {@code active (running)}, а
     * {@code /all} показывал 🟢 «торгует»: флаг котирования берётся из журнала, а
     * там записан {@code start}. Единственным признаком была строка «тик 23 ч» в
     * конце блока, и её никто не прочитал.
     *
     * <h2>Почему именно тик, а не сердцебиение реестра</h2>
     *
     * Сердцебиение живёт в {@code alloc.db} и есть только у бота, за которым
     * что-то записано. Бот с нулевой претензией (а после массового
     * {@code /release} такими были все пятеро) в реестре невидим, а тик пишет
     * каждый работающий котировщик независимо ни от чего.
     *
     * <h2>Тревога, а не строка в сводке</h2>
     *
     * По правилу владельца будить можно тем, что внутри не сходится. Бот,
     * который числится включённым и при этом не тикает, — ровно этот случай:
     * он не работает и сам об этом сказать не может. Повтор не чаще раза в час,
     * и отдельным сообщением — когда ожил.
     */
    void watchSilence() {                                // пакетный доступ: тест
        long now = System.currentTimeMillis();
        for (Watched w : watched) {
            // ⚠️ НЕ read(w): тот строит книгу партий по ВСЕМ исполнениям журнала,
            // а сторожу нужны два числа. На машине одно ядро на шесть ботов и
            // сборщик, и отчёт раз в двадцать пять секунд обошёлся бы дороже
            // того, что он сторожит.
            long lastTick;
            boolean quoting;
            try (ExecJournal j = ExecJournal.readOnly(w.journalPath())) {
                quoting = j.quotingOn();
                lastTick = j.lastQuoteMs();
            } catch (Exception e) {
                continue;             // журнала нет — про это скажет /all строкой
            }
            String id = w.botId().toLowerCase(Locale.ROOT);
            boolean silent = quoting && lastTick > 0 && now - lastTick > SILENCE_MS;
            Long told = silenceTold.get(id);
            if (!silent) {
                if (told != null) {
                    silenceTold.remove(id);
                    send("🟢 " + w.botId().toUpperCase(Locale.ROOT) + " " + w.symbol()
                            + " снова тикает.");
                }
                continue;
            }
            if (told != null && now - told < 3_600_000L) {
                continue;                                // уже сказали, ждём час
            }
            silenceTold.put(id, now);
            send(("💀 %s %s МОЛЧИТ %s: котирование числится ВКЛЮЧЁННЫМ, а тиков нет.%n"
                    + "Поток котирования мёртв — systemd этого не видит, он показывает "
                    + "active (running).%n"
                    + "Проверить: есть ли в процессе поток revx-quote-loop; если нет — "
                    + "перезапустить службу.%n"
                    + "⚠️ Самая частая причина — app.jar перезаписан под работающим "
                    + "процессом (классы грузятся лениво).")
                    .formatted(w.botId().toUpperCase(Locale.ROOT), w.symbol(),
                            ago(now - lastTick)));
        }
    }

    private String all() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("СВОДКА ПО ИСПОЛНИТЕЛЯМ\n\n");
        double totalRealised = 0;
        double totalInventory = 0;
        long totalPlacements = 0;
        long totalCap = 0;
        for (Watched w : visible()) {
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
            // ⚠️ ЧЕТВЁРТОЕ СОСТОЯНИЕ — 💀 МОЛЧИТ, и оно старше остальных трёх.
            //
            // Флаг котирования берётся из журнала, а журнал помнит команду
            // человека, а не то, жив ли поток. 16.09.2026 пять мёртвых ботов
            // двадцать три часа показывались зелёными «торгует» (задача A64):
            // поток котирования упал, а запись `start` в журнале осталась.
            // Поэтому молчание проверяется ПЕРЕД тем, как верить флагу.
            boolean silent = s.quoting() && s.lastEventMs() > 0
                    && now - s.lastEventMs() > SILENCE_MS;
            String mark = silent ? "💀" : !s.quoting() ? "⚪" : s.trading() ? "🟢" : "🟡";
            String what = silent
                    ? "МОЛЧИТ " + age + " — поток котирования мёртв (systemd этого не видит)"
                    : !s.quoting() ? "выключен"
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
                    "%s %s  %s%s — %s%n  закрытых пар 24ч %d, доход %+.4f USDC%n"
                            + "  инвентарь %.2f USDC%s, постановок %d из %d (%d%%)%n"
                            + "  оборот 24ч %.2f USDC, доход %+.1f б.п. оборота%n"
                            + "  отводов за час %d, тик %s%s%n%n",
                    mark, s.botId().toUpperCase(Locale.ROOT), s.symbol(),
                    s.form() == null || s.form().isEmpty() ? "" : " · " + s.form(), what,
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
        // ⚠️ Итоги считаются по ПОКАЗАННЫМ — иначе сумма не сходилась бы со
        // строками выше, а это худший вид неправды в отчёте.
        sb.append(hiddenNote());
        sb.append(unownedNote());
        return sb.toString();
    }

    /**
     * НИЧЕЙНОЕ — одной строкой в общей сводке.
     *
     * ⚠️ Это самая незаметная из поломок: монета лежит на счёте, ни один бот ею
     * не торгует, и НИКТО об этом не сообщает. Владелец находит её глазами на
     * сайте площадки — так было 10.09.2026 (лот пролежал ничейным восемь часов)
     * и 13.09.2026 (по лоту на каждой из трёх пар после {@code /release}).
     * Отдельная команда {@code /alloc} для этого не годится: чтобы её набрать,
     * надо уже подозревать неладное.
     *
     * Показываются только валюты, где ничейного больше 1% остатка, — крошки
     * округления и запаздывание снимка (реестр двигается на каждом исполнении, а
     * остаток опрашивается раз в минуту) тревоги не стоят.
     */
    private String unownedNote() {
        java.util.Map<String, Double> claimed = new java.util.TreeMap<>();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + allocPath + "?mode=ro");
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT currency, sum(qty) FROM claim GROUP BY currency")) {
            while (rs.next()) {
                claimed.put(rs.getString(1), rs.getDouble(2));
            }
        } catch (Exception e) {
            return "";                    // реестра нет — молчим, /alloc объяснит подробно
        }
        Balances bal = venueBalances();
        // Складываем в список, чтобы отсортировать по ДЕНЬГАМ: количество само по
        // себе несравнимо (7250 PEPE против 0.0472 SOL), а сверху списка должно
        // стоять то, чего жалко.
        record Row(String currency, double qty, double usd) { }
        java.util.Map<String, Double> qty = new java.util.LinkedHashMap<>();
        for (var e : bal.byCurrency().entrySet()) {
            double total = e.getValue().total();
            double free = total - claimed.getOrDefault(e.getKey(), 0.0);
            if (total > 0 && free > 0.01 * total) {
                qty.put(e.getKey(), free);
            }
        }
        if (qty.isEmpty()) {
            return "";
        }
        // Цены спрашиваем ТОЛЬКО про то, что попало в список: ответ остатков
        // содержит четыре десятка валют, почти все с нулём, и платить за них
        // запросом к базе сборщика незачем.
        java.util.Map<String, Double> px = prices(qty.keySet());
        java.util.List<Row> rows = new java.util.ArrayList<>();
        qty.forEach((currency, free) -> {
            Double p = px.get(currency);
            rows.add(new Row(currency, free, p == null ? Double.NaN : free * p));
        });
        rows.sort((x, y) -> Double.compare(Double.isNaN(y.usd()) ? -1 : y.usd(),
                Double.isNaN(x.usd()) ? -1 : x.usd()));
        StringBuilder sb = new StringBuilder();
        double sum = 0;
        boolean gaps = false;
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "\n  %-5s %12s", r.currency(), trim(r.qty())));
            if (Double.isNaN(r.usd())) {
                // Цены нет — молчим о ней прямо, а не показываем ноль: ноль здесь
                // читался бы как «ничего не стоит».
                sb.append("  (цены нет)");
                gaps = true;
            } else {
                sb.append(String.format(Locale.ROOT, "  %8.2f USDC", r.usd()));
                sum += r.usd();
            }
        }
        return "\n\n⚠️ НИЧЕЙНОЕ (ни за кем не числится):" + sb
                + String.format(Locale.ROOT, "\n  всего %.2f USDC%s", sum,
                        gaps ? " (без тех, у кого нет цены)" : "")
                + "\nэтим никто не торгует — подробности /alloc";
    }

    /**
     * Курс монет в USDC из базы сборщика: середина последнего снимка книги.
     *
     * ⚠️ СПРАШИВАТЬ НАДО ПО ОДНОЙ ПАРЕ, и это не стилистика. База сборщика
     * весит 7.7 ГБ, и запрос «последняя цена всех пар» через {@code GROUP BY
     * symbol} сканирует индекс целиком — замерено на живой базе, **64 секунды**.
     * Тот же ответ по одной паре через {@code ORDER BY t_recv_ms DESC LIMIT 1}
     * ложится на индекс {@code (symbol, t_recv_ms)} и занимает **2 мс**. Сводка
     * собирается по запросу человека, ей минута ожидания недопустима.
     *
     * Свежесть НЕ проверяется намеренно: если сборщик встал, честнее показать
     * цену часовой давности, чем не показать ничего. Ничейное читают, чтобы
     * понять порядок величины, а не чтобы торговать по этой цене.
     *
     * USDC сам себе цена: без этого он выпал бы из суммы, а его в ничейном
     * обычно больше всего.
     */
    private java.util.Map<String, Double> prices(java.util.Collection<String> currencies) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        out.put("USDC", 1.0);
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + bookPath + "?mode=ro");
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT bp1, ap1 FROM revx_book WHERE symbol = ?"
                             + " ORDER BY t_recv_ms DESC LIMIT 1")) {
            for (String currency : currencies) {
                if (out.containsKey(currency)) {
                    continue;
                }
                ps.setString(1, currency + "/USDC");
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getDouble(1) > 0 && rs.getDouble(2) > 0) {
                        out.put(currency, (rs.getDouble(1) + rs.getDouble(2)) / 2);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("цены недоступны ({}): покажем ничейное без денег", e.toString());
        }
        return out;
    }


    /**
     * РЕЗЕРВАЦИИ: кто из ботов что за собой держит.
     *
     * <h2>Зачем отдельная команда</h2>
     *
     * Счёт на площадке ОБЩИЙ, а делят его шесть ботов через реестр
     * {@code alloc.db}. До сих пор увидеть раскладку можно было только запросом
     * к базе руками, и это уже стоило времени: 12.09.2026 при переводе ботов на
     * три уровня половина из них упёрлась в нехватку кассы, а понять это удалось
     * не по сводке, а по счётчику {@code no_funds} в журналах.
     *
     * ⚠️ И ещё это ловит расхождение, которое иначе видно только по симптомам:
     * бот показывает ноль монеты, а на счёте она есть — значит монета НИЧЕЙНАЯ
     * (разбор 10.09.2026). Здесь такое видно сразу: сумма резерваций меньше
     * остатка счёта.
     *
     * <h2>Откуда берутся числа</h2>
     *
     * Резервации — из {@code alloc.db}, который лежит на той же машине и
     * открывается ТОЛЬКО НА ЧТЕНИЕ. Остаток счёта — из последнего ответа
     * {@code GET /balances}, записанного любым из ботов в свой журнал.
     *
     * 🔑 Ключа у сводки по-прежнему нет и быть не должно: она не ходит на
     * площадку вообще, а пользуется тем, что боты уже записали.
     */
    private String alloc() {
        java.util.Map<String, java.util.Map<String, double[]>> byCurrency = new java.util.TreeMap<>();
        long now = System.currentTimeMillis();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + allocPath + "?mode=ro");
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT bot_id, currency, qty, heartbeat_ms FROM claim ORDER BY currency, bot_id")) {
            while (rs.next()) {
                byCurrency.computeIfAbsent(rs.getString(2), k -> new java.util.TreeMap<>())
                        .put(rs.getString(1), new double[]{rs.getDouble(3), rs.getLong(4)});
            }
        } catch (Exception e) {
            return "Реестр резерваций не прочитался: " + e.getMessage()
                    + "\n(ожидался " + allocPath + ")";
        }
        if (byCurrency.isEmpty()) {
            return "Реестр резерваций пуст.";
        }
        Balances bal = venueBalances();
        java.util.Map<String, Balance> onVenue = bal.byCurrency();
        java.util.List<ActiveOrder> orders = venueOrders();

        StringBuilder sb = new StringBuilder("РЕЗЕРВАЦИИ\n");
        if (bal.tsMs() > 0) {
            sb.append("остаток счёта снят ").append(ago(now - bal.tsMs())).append('\n');
        }
        sb.append('\n');
        for (var cur : byCurrency.entrySet()) {
            double claimed = 0;
            StringBuilder rows = new StringBuilder();
            for (var bot : cur.getValue().entrySet()) {
                double qty = bot.getValue()[0];
                long beat = (long) bot.getValue()[1];
                claimed += qty;
                // ⚠️ Мёртвый бот продолжает держать резервацию, пока её не
                // распустят. Отметка времени показывает, жив ли он.
                String stale = now - beat > 10 * 60_000L
                        ? "  ⚠️ молчит " + ago(now - beat) : "";
                // ⚠️ НУЛЕВЫЕ ПРЯЧЕМ, НО НЕ МОЛЧАЩИЕ (задача A64). После массового
                // `/release` 16.09.2026 у всех пяти мёртвых ботов претензия по
                // монете была нулём — и они пропали из этой таблицы целиком,
                // ровно тогда, когда о них надо было сказать.
                if (qty <= 0 && stale.isEmpty()) {
                    continue;                      // нулевые не показываем: их много и они пусты
                }
                rows.append(String.format(Locale.ROOT, "  %s  %s%s%n",
                        bot.getKey().toUpperCase(Locale.ROOT), trim(qty), stale));
            }
            if (rows.isEmpty()) {
                continue;
            }
            Balance v = onVenue.get(cur.getKey());
            sb.append(cur.getKey()).append(':');
            if (v != null) {
                double free = v.total() - claimed;
                double orphan = orphanLocked(cur.getKey(), orders, cur.getValue(), now);
                // ⚠️ Тревога только при КРУПНОЙ недостаче. Реестр двигается на
                // каждом исполнении, а остаток опрашивается раз в минуту, поэтому
                // расхождение в один лот — это почти всегда запаздывание снимка.
                // Кричать на него значит приучить не смотреть на предупреждения.
                String note = free >= -1e-12 ? ""
                        : (-free > 0.2 * Math.max(1e-12, claimed)
                        ? "  ⚠️ разобрано БОЛЬШЕ, чем есть"
                        : "  (снимок отстал — обычно ровно на лот)");
                sb.append(String.format(Locale.ROOT,
                        "  на счёте %s (в заявках %s), разобрано %s, СВОБОДНО %s%s",
                        trim(v.total()), trim(v.reserved()), trim(claimed), trim(free), note));
                // ⚠️ Свободное свободному рознь. Монета, запертая в заявке БЕЗ
                // живого хозяина, в остатке видна, но забрать её нельзя: заявка
                // исполнится сама, и у нового владельца останется фантом.
                if (orphan > 1e-12) {
                    sb.append(String.format(Locale.ROOT,
                            "%n  ⚠️ из них %s заперто в заявках без живого хозяина — "
                                    + "сначала снять эти заявки", trim(orphan)));
                } else if (free > 1e-12) {
                    sb.append("\n  → забрать: /stop, затем /claim всё у нужного бота");
                }
            } else {
                sb.append(String.format(Locale.ROOT, "  разобрано %s (остаток счёта неизвестен)",
                        trim(claimed)));
            }
            sb.append('\n').append(rows).append('\n');
        }
        sb.append("⚠️ Свободное — это НИЧЕЙНОЕ: им никто не торгует, пока кто-нибудь\n")
                .append("не заберёт его через /claim. Резервация мёртвого бота держится\n")
                .append("до роспуска, поэтому «молчит» рядом с числом важнее самого числа.\n")
                .append("Считается по total: монета в выставленной заявке лежит в reserved.\n");
        if (onVenue.isEmpty()) {
            sb.append("\n⚠️ Остатков счёта нет: ни один журнал не содержит ответа /balances.\n");
        }
        return sb.toString();
    }

    /**
     * Сколько монеты заперто в продажах, у которых НЕТ живого хозяина в реестре.
     *
     * Такая монета вдвойне обманчива: в остатке счёта она есть, резервацией не
     * покрыта — то есть выглядит свободной, — а забрать её нельзя. Заявка стоит
     * сама по себе (бот убит, а заявка осталась; или заявка досталась от версии
     * без метки) и однажды исполнится, оставив нового владельца с фантомом.
     *
     * Хозяин определяется по метке в {@code client_order_id}: первые восемь
     * знаков — {@link BotTag#prefix()} владельца. Заявка без метки хозяина не
     * имеет по определению.
     *
     * @param claims строки реестра по этой валюте: {@code bot -> [qty, heartbeat]}
     */
    static double orphanLocked(String currency, java.util.List<ActiveOrder> orders,
                                       java.util.Map<String, double[]> claims, long nowMs) {
        double orphan = 0;
        for (ActiveOrder o : orders) {
            if (o.side() != org.home.data.revx.sim.Side.SELL || o.symbol() == null
                    || !o.symbol().startsWith(currency + "/")) {
                continue;                  // монету запирает только продажа
            }
            boolean liveOwner = false;
            for (var claim : claims.entrySet()) {
                boolean live = nowMs - (long) claim.getValue()[1] < AllocRegistry.LEASE_MS;
                if (live && new BotTag(claim.getKey()).owns(o.clientId())) {
                    liveOwner = true;
                    break;
                }
            }
            if (!liveOwner) {
                orphan += Math.max(0, o.size());
            }
        }
        return orphan;
    }

    /**
     * Активные заявки из последнего ответа {@code GET /orders/active} в журналах.
     *
     * Список общий на весь счёт, поэтому годится ЛЮБОЙ журнал — берётся самый
     * свежий. Ключа у сводки нет, на площадку она не ходит.
     */
    private java.util.List<ActiveOrder> venueOrders() {
        java.util.List<ActiveOrder> best = java.util.List.of();
        long bestMs = 0;
        for (Watched w : watched) {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 java.sql.Statement st = c.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(
                         "SELECT ts_ms, response FROM exec_request WHERE path LIKE '%orders/active%'"
                                 + " AND status = 200 ORDER BY ts_ms DESC LIMIT 1")) {
                if (rs.next() && rs.getLong(1) > bestMs) {
                    bestMs = rs.getLong(1);
                    best = ActiveOrder.parse(rs.getString(2));
                }
            } catch (Exception ignore) {
                // журнал недоступен — не беда, попробуем следующий
            }
        }
        return best;
    }

    /** Остаток одной валюты на счёте: что можно тратить, что заперто в заявках. */
    private record Balance(double available, double reserved) {
        double total() {
            return available + reserved;
        }
    }

    /**
     * Остатки счёта из последнего ответа {@code /balances} в журналах ботов.
     *
     * ⚠️ СЧИТАТЬ НАДО ПО {@code total}, А НЕ ПО {@code available} — первая версия
     * брала available и показывала «разобрано больше, чем есть» на КАЖДОЙ паре,
     * где бот стоит в книге. Монета, лежащая в выставленной заявке, сидит в
     * {@code reserved}: у BTC 13.09.2026 было available 0.00000019 при reserved
     * 0.00003764. Резервация покрывает монету независимо от того, заперта она
     * сейчас в заявке или нет.
     *
     * Возвращается и отметка времени снимка: остаток опрашивается раз в минуту, а
     * реестр двигается на каждом исполнении, поэтому расхождение РОВНО В ЛОТ —
     * это почти всегда запаздывание снимка, а не потеря.
     */
    private record Balances(java.util.Map<String, Balance> byCurrency, long tsMs) {
    }

    private Balances venueBalances() {
        java.util.Map<String, Balance> out = new java.util.HashMap<>();
        long best = 0;
        for (Watched w : watched) {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 java.sql.Statement st = c.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(
                         "SELECT ts_ms, response FROM exec_request WHERE path LIKE '%balances%'"
                                 + " AND status = 200 ORDER BY ts_ms DESC LIMIT 1")) {
                if (!rs.next() || rs.getLong(1) <= best) {
                    continue;
                }
                // Берём САМЫЙ СВЕЖИЙ ответ из всех журналов: остаток общий, и
                // старый снимок соврал бы ровно там, где важна точность.
                best = rs.getLong(1);
                out.clear();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "\"currency\"\\s*:\\s*\"([A-Z0-9]+)\"\\s*,\\s*\"available\"\\s*:\\s*\"([0-9.]+)\""
                                + "\\s*,\\s*\"reserved\"\\s*:\\s*\"([0-9.]+)\"")
                        .matcher(rs.getString(2) == null ? "" : rs.getString(2));
                while (m.find()) {
                    out.put(m.group(1), new Balance(Double.parseDouble(m.group(2)),
                            Double.parseDouble(m.group(3))));
                }
            } catch (Exception ignore) {
                // журнал недоступен — не беда, попробуем следующий
            }
        }
        return new Balances(out, best);
    }

    /** Короткая запись количества: монеты и деньги требуют разной точности. */
    private static String trim(double v) {
        double a = Math.abs(v);
        if (a >= 100) {
            return String.format(Locale.ROOT, "%.2f", v);
        }
        return a >= 0.01 ? String.format(Locale.ROOT, "%.4f", v)
                : String.format(Locale.ROOT, "%.8f", v);
    }
    private String pnl() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("ДОХОД ПО ИСПОЛНИТЕЛЯМ\n\n");
        sb.append(String.format(Locale.ROOT, "%-4s %-10s %10s %10s %10s%n",
                "бот", "пара", "24 часа", "7 суток", "в позиции"));
        double d1 = 0;
        double d7 = 0;
        double unreal = 0;
        for (Watched w : visible()) {
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
        sb.append(hiddenNote());
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
                     {"command":"alloc","description":"кто что держит и сколько ничейного"},
                     {"command":"hide","description":"убрать ботов из сводок: /hide d e f"},
                     {"command":"show","description":"вернуть: /show d или /show all"},
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
