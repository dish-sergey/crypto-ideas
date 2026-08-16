package org.home.data.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Режим запуска: one-shot (--collect / --backfill, после выполнения выход)
 * либо режим планировщика (без аргументов).
 */
@Component
public class CliMode {

    private final boolean scheduleMode;

    public CliMode(ApplicationArguments args) {
        this.scheduleMode = !args.containsOption("collect") && !args.containsOption("backfill")
                && !args.containsOption("report") && !args.containsOption("s5-dry-run")
                && !args.containsOption("s5-kraken-check") && !args.containsOption("s5-demo");
    }

    public boolean isScheduleMode() {
        return scheduleMode;
    }
}
