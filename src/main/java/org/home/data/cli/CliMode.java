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
                && !args.containsOption("s5-kraken-check") && !args.containsOption("s5-demo")
                && !args.containsOption("s5-live")
                && !args.containsOption("revx-pairs") && !args.containsOption("revx-probe-limits")
                && !args.containsOption("revx-collect") && !args.containsOption("revx-basis")
                && !args.containsOption("revx-sim") && !args.containsOption("revx-flow")
                && !args.containsOption("revx-trade-check") && !args.containsOption("revx-order-schema")
                && !args.containsOption("theory");
    }

    public boolean isScheduleMode() {
        return scheduleMode;
    }
}
