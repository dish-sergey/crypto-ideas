package org.home.data.revx.exec;

import org.home.data.revx.replay.BootParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * {@code --revx-release-stale}: снять ОСИРОТЕВШУЮ претензию на монету.
 *
 * <h2>Зачем понадобилась отдельная команда</h2>
 *
 * Реестр меняется только через собственные {@code /claim} и {@code /release}
 * бота — правило, которое держит журнал и реестр в согласии. Но {@code /release}
 * умеет отдавать лишь ТЕКУЩУЮ пару бота, и за пределами этой пары правило
 * перестаёт что-либо защищать.
 *
 * Живой случай (16.09.2026): бот {@code d} торговал ADA, пару вывели из работы,
 * бот перевели на ETH. В реестре осталась претензия {@code d → 59.7754 ADA}. Она
 * не истекает — аренда продлевается по метке бота, а процесс {@code d} жив, — и
 * снять её нечем: {@code /release} у {@code d} отдаёт ETH, а не ADA.
 *
 * ⚠️ Почему это БЕЗОПАСНО, хотя обходит бота: синхронизировать нечего. В журнале
 * {@code d} никакой ADA-позиции нет, её там и не было с момента перевода на ETH.
 * Правило «только через бота» защищает согласие журнала с реестром, а у
 * осиротевшей монеты второй половины этого согласия попросту не существует.
 *
 * ⚠️ Обходной путь «поднять временный процесс с меткой d и символом ADA» —
 * ХУЖЕ, и рассматривался первым. {@link QuoteLoop#release()} пишет строку
 * передачи в журнал бота и двигает его {@code position}: временный процесс
 * испортил бы ETH-журнал {@code d} ADA-сделкой, да ещё заодно отдал бы в котёл
 * его кассу, потому что {@code release} отдаёт обе валюты сразу.
 *
 * <h2>Предохранитель</h2>
 *
 * Команда обязана убедиться, что монета действительно осиротела, и делает это
 * единственным честным способом — спрашивает сам журнал бота, чем он торгует
 * СЕЙЧАС. Если монета совпадает с базовой или котируемой валютой его нынешней
 * пары, это не сирота, а живой инвентарь: такую снимает {@code /release}, и
 * команда отказывается.
 */
@Component
@Lazy
public class ReleaseStale {

    private static final Logger log = LoggerFactory.getLogger(ReleaseStale.class);

    private final String defaultAlloc;

    public ReleaseStale(@Value("${revx.exec.alloc}") String defaultAlloc) {
        this.defaultAlloc = defaultAlloc;
    }

    public void run(String botId, String currency, String journalPath, String allocOverride) {
        if (botId == null || botId.isBlank() || currency == null || currency.isBlank()) {
            log.error("нужны --bot=<метка> и --currency=<монета>");
            return;
        }
        if (journalPath == null || journalPath.isBlank()) {
            log.error("нужен --journal=<путь к журналу этого бота>: без него не проверить, "
                    + "что монета действительно осиротела");
            return;
        }
        String coin = currency.trim().toUpperCase(Locale.ROOT);
        String alloc = allocOverride == null || allocOverride.isBlank() ? defaultAlloc : allocOverride;

        String trading = tradedSymbol(journalPath);
        if (trading == null) {
            log.error("в журнале {} нет записи boot — чем бот торгует, выяснить нечем; "
                    + "не трогаю реестр", journalPath);
            return;
        }
        String base = trading.substring(0, trading.indexOf('/'));
        String quote = trading.substring(trading.indexOf('/') + 1);
        if (coin.equals(base) || coin.equals(quote)) {
            log.error("бот {} ТОРГУЕТ {} прямо сейчас (пара {}) — это не осиротевшая претензия. "
                    + "Отдавать живой инвентарь надо командой /release у самого бота",
                    botId, coin, trading);
            return;
        }

        try (AllocRegistry registry = new AllocRegistry(alloc)) {
            double own = registry.own(botId, coin);
            if (!(own > 0)) {
                log.info("за ботом {} не числится {} — снимать нечего", botId, coin);
                return;
            }
            log.warn("бот {} торгует {}, а в реестре за ним висит {} {} — снимаю претензию",
                    botId, trading, fmt(own), coin);
            // Цена нулевая намеренно: это не передача инвентаря по рыночной цене,
            // а списание записи, за которой нет позиции. Цена в событии реестра
            // осталась бы выдумкой, а выдумка в журнале хуже пустоты.
            registry.release(botId, coin, 0, System.currentTimeMillis());
            double left = registry.own(botId, coin);
            if (left > 0) {
                log.error("после снятия за ботом всё ещё {} {} — реестр не принял запись",
                        fmt(left), coin);
                return;
            }
            log.warn("готово: {} {} стали ничейными. Забрать их может любой бот через /claim, "
                    + "а увидеть — сводный бот и --revx-audit", fmt(own), coin);
        }
    }

    /**
     * Чем бот торгует сейчас — по последней записи {@code boot} в его журнале.
     *
     * Берётся именно журнал, а не конфигурация: настройки живого бота приходят из
     * systemd-юнита, и подстановка умолчаний однажды уже превратила сверку в
     * сравнение двух разных ботов (см. шапку {@code revx/replay}).
     */
    private static String tradedSymbol(String journalPath) {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + journalPath + "?mode=ro");
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT detail FROM exec_event WHERE kind = 'boot'"
                             + " ORDER BY ts_ms DESC LIMIT 1")) {
            if (!rs.next()) {
                return null;
            }
            BootParams p = BootParams.parse(rs.getString(1));
            return p == null ? null : p.symbol();
        } catch (Exception e) {
            log.error("журнал {} не читается: {}", journalPath, e.toString());
            return null;
        }
    }

    private static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(8, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
