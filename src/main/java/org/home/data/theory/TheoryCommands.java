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

    public TheoryCommands(S5EventImporter s5Importer, AllocBench allocBench,
                          org.home.data.theory.kelly.KellyBench kellyBench,
                          org.home.data.theory.ou.OuBench ouBench,
                          org.home.data.theory.band.BandBench bandBench) {
        this.s5Importer = s5Importer;
        this.allocBench = allocBench;
        this.kellyBench = kellyBench;
        this.ouBench = ouBench;
        this.bandBench = bandBench;
    }

    /** {@code --theory=<target>}; {@code out} — каталог отчётов. */
    public void run(String target, String out) {
        switch (target) {
            case "s5-import" -> s5Importer.run();
            case "alloc" -> allocBench.run(out);
            case "kelly" -> kellyBench.run(out);
            case "ou" -> ouBench.run(out);
            case "band" -> bandBench.run(out);
            default -> throw new IllegalArgumentException(
                    "Неизвестный стенд: " + target + " (s5-import | alloc | kelly | ou | band)");
        }
    }
}
