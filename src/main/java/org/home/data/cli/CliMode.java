package org.home.data.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Режим запуска: one-shot (команда, после выполнения выход) либо планировщик.
 *
 * ⚠️ **Планировщик включается только при ПОЛНОМ отсутствии команд, и неизвестная
 * команда сюда не проваливается.** Раньше условие было чистым чёрным списком:
 * всё, что не перечислено, считалось «аргументов нет» и запускало планировщик со
 * всеми коллекторами и WS-ликвидациями.
 *
 * Это не теоретическая придирка. 27.08.2026 на bot-arm запустили
 * {@code --revx-panic} джарником, собранным до появления этой команды: опция не
 * распозналась, приложение сочло, что аргументов нет, и подняло на торговом
 * сервере весь слой данных — 31 МБ мусорной базы за две минуты, пока это не
 * заметили. Ровно та же ошибка случалась 19.08.2026 (см. deploy/SERVERS.md).
 *
 * Свойства Spring (вида {@code --revx.sim.offset=0.0014}) командами не считаются:
 * они содержат точку и служат для переопределения конфигурации.
 */
@Component
public class CliMode {

    /** Все известные команды. Добавил команду в CliRunner — добавь и сюда. */
    private static final Set<String> COMMANDS = Set.of(
            "collect", "backfill", "report", "theory",
            "s5-dry-run", "s5-kraken-check", "s5-demo", "s5-live",
            "revx-pairs", "revx-probe-limits", "revx-collect", "revx-basis",
            "revx-sim", "revx-flow", "revx-screen", "revx-exec-report",
            "revx-trade-check", "revx-order-probe", "revx-panic", "revx-exec", "revx-regimes");

    /**
     * Параметры команд. Их нельзя считать неизвестными опциями: они не запускают
     * ничего сами, а уточняют команду рядом.
     *
     * Разделение появилось не сразу — проверка на неизвестную опцию сначала знала
     * только команды и заворачивала законный вызов
     * {@code --revx-sim --symbol=… --hours=…} целиком.
     */
    private static final Set<String> PARAMS = Set.of(
            "symbol", "symbols", "hours", "from", "to", "interval",
            "out", "table", "bucket-seconds", "days", "edge", "up", "down", "flat",
            "journal", "offset");

    private final boolean scheduleMode;
    private final Set<String> unknownOptions;

    public CliMode(ApplicationArguments args) {
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> orphanParams = new LinkedHashSet<>();
        boolean anyCommand = false;
        for (String name : args.getOptionNames()) {
            if (COMMANDS.contains(name)) {
                anyCommand = true;
            } else if (PARAMS.contains(name)) {
                orphanParams.add(name);       // законен только рядом с командой
            } else if (!name.contains(".")) {
                unknown.add(name);
            }
        }
        // Параметр без команды — тоже ошибка, и молчать о ней нельзя: иначе
        // опечатка в имени команды (`--revx-sym --symbol=BTC/USDC`) оставила бы
        // одни параметры и подняла планировщик вместо запрошенного прогона.
        if (anyCommand) {
            orphanParams.clear();
        }
        unknown.addAll(orphanParams);
        this.unknownOptions = Set.copyOf(unknown);
        this.scheduleMode = !anyCommand && unknown.isEmpty();
    }

    public boolean isScheduleMode() {
        return scheduleMode;
    }

    /**
     * Опции, которые не являются ни командой, ни свойством Spring. Если такие есть,
     * запускать нечего: это либо опечатка, либо джарник старее команды.
     */
    public Set<String> unknownOptions() {
        return unknownOptions;
    }
}
