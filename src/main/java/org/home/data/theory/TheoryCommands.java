package org.home.data.theory;

import org.home.data.theory.alloc.AllocBench;
import org.home.data.theory.s5.S5EventImporter;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * CLI-фасад счётных стендов теории оптимальности (ТЗ docs/65–68). Отдельным
 * классом, чтобы {@link org.home.data.cli.CliRunner} тянул его через
 * ObjectProvider: в режиме планировщика бины стендов не создаются и файл
 * data/theory.db не появляется.
 */
@Component
@Lazy
public class TheoryCommands {

    private final S5EventImporter s5Importer;
    private final AllocBench allocBench;
    private final org.home.data.theory.kelly.KellyBench kellyBench;
    private final org.home.data.theory.ou.OuBench ouBench;
    private final org.home.data.theory.band.BandBench bandBench;
    private final org.home.data.theory.ou.PerpMinuteImporter perpImporter;
    private final org.home.data.theory.ou.BasisVerify basisVerify;

    public TheoryCommands(S5EventImporter s5Importer, AllocBench allocBench,
                          org.home.data.theory.kelly.KellyBench kellyBench,
                          org.home.data.theory.ou.OuBench ouBench,
                          org.home.data.theory.band.BandBench bandBench,
                          org.home.data.theory.ou.PerpMinuteImporter perpImporter,
                          org.home.data.theory.ou.BasisVerify basisVerify) {
        this.s5Importer = s5Importer;
        this.allocBench = allocBench;
        this.kellyBench = kellyBench;
        this.ouBench = ouBench;
        this.bandBench = bandBench;
        this.perpImporter = perpImporter;
        this.basisVerify = basisVerify;
    }

    /** {@code --theory=<target>}; {@code out} — каталог отчётов. */
    public void run(String target, String out) {
        switch (target) {
            case "s5-import" -> s5Importer.run();
            case "alloc" -> allocBench.run(out);
            case "kelly" -> kellyBench.run(out);
            case "ou" -> ouBench.run(out);
            case "band" -> bandBench.run(out);
            case "verify" -> basisVerify.run(out);
            case "basis-import" -> perpImporter.run(java.util.List.of("BTCUSDT", "ETHUSDT"));
            case "basis-stress" -> perpImporter.runStress(java.util.List.of("BTCUSDT"), 20, "2024-01-01");
            case "basis-history" -> perpImporter.runContinuous(java.util.List.of("BTCUSDT"), "2025-08-01");
            default -> throw new IllegalArgumentException(
                    "Неизвестный стенд: " + target + " (s5-import | basis-import | basis-stress | basis-history | alloc | kelly | ou | band | verify)");
        }
    }
}
