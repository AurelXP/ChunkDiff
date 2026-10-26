package dev.chunkdiff;

import dev.chunkdiff.cli.Compactor;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * S'execute au tout debut du demarrage, AVANT le chargement des mondes : les fichiers .mca ne sont
 * pas encore ouverts par le serveur, on peut les reecrire sans risque.
 * Declenche uniquement si l'admin a tape /chunkdiff compact confirm (cree plans/APPLY).
 */
public final class ChunkDiffBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        var log = context.getLogger();
        Path plans = context.getDataDirectory().resolve("plans");
        Path marker = plans.resolve("APPLY");
        if (!Files.exists(marker)) return;

        try {
            Files.deleteIfExists(marker); // une seule execution, meme en cas de plantage
        } catch (IOException e) {
            log.error("ChunkDiff : impossible de supprimer le marqueur APPLY, compaction annulee : " + e.getMessage());
            return;
        }

        List<Path> list;
        try (Stream<Path> s = Files.list(plans)) {
            list = s.filter(p -> p.getFileName().toString().endsWith(".plan")).sorted().toList();
        } catch (IOException e) {
            log.error("ChunkDiff : lecture du dossier plans impossible : " + e.getMessage());
            return;
        }
        log.info("ChunkDiff : compaction demandee, " + list.size() + " plan(s) a appliquer AVANT le chargement des mondes.");
        for (Path plan : list) {
            try {
                Compactor.run(plan, null, true, true, msg -> log.info(msg));
                Files.move(plan, plan.resolveSibling(plan.getFileName() + ".done"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                log.error("ChunkDiff : compaction ABANDONNEE pour " + plan.getFileName() + " : " + e.getMessage()
                        + " (les fichiers deja traites ont une sauvegarde chunkdiff-backup-*).");
            }
        }
    }
}
