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

    public InfoBot(String token, long chatId, List<Watched> watched) {
        this(token, chatId, watched,
                System.getProperty("revx.info.alloc", "../revx-shared/alloc.db"));
    }

    public InfoBot(String token, long chatId, List<Watched> watched, String allocPath) {
        this.token = token;
        this.chatId = chatId;
        this.watched = watched;
        this.allocPath = allocPath;
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
                + "/hide d e f — убрать лишних с глаз, /show all — вернуть.");
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

                    /all — котирование, инвентарь, сделки и доход за сутки
                    /pnl — доход за 24 часа и за 7 суток, плюс нереализованное
                    /alloc — кто что держит за собой и сколько ничейного
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
        // ⚠️ Итоги считаются по ПОКАЗАННЫМ — иначе сумма не сходилась бы со
        // строками выше, а это худший вид неправды в отчёте.
        sb.append(hiddenNote());
        return sb.toString();
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
                if (qty <= 0) {
                    continue;                      // нулевые не показываем: их много и они пусты
                }
                // ⚠️ Мёртвый бот продолжает держать резервацию, пока её не
                // распустят. Отметка времени показывает, жив ли он.
                String stale = now - beat > 10 * 60_000L
                        ? "  ⚠️ молчит " + ago(now - beat) : "";
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
