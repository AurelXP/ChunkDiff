package dev.chunkdiff.cli;

/**
 * Outil en ligne de commande (OPTIONNEL, pour ceux qui ont un acces hors ligne) :
 *   java -jar ChunkDiff-x.jar compact <fichier.plan> [--region <dossier>] [--apply]
 * Sur un hebergeur en ligne, utiliser plutot /chunkdiff compact confirm + redemarrage.
 */
public final class CompactMain {
    public static void main(String[] args) {
        try {
            if (args.length < 2 || !args[0].equals("compact")) {
                System.out.println("Usage : java -jar ChunkDiff.jar compact <fichier.plan> [--region <dossier>] [--apply]");
                return;
            }
            java.nio.file.Path planFile = java.nio.file.Path.of(args[1]);
            boolean apply = false;
            java.nio.file.Path region = null;
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("--apply")) apply = true;
                else if (args[i].equals("--region") && i + 1 < args.length) region = java.nio.file.Path.of(args[++i]);
                else throw new java.io.IOException("argument inconnu : " + args[i]);
            }
            Compactor.run(planFile, region, apply, true, System.out::println);
            if (!apply) System.out.println("Simulation terminee. Relancez avec --apply (serveur ARRETE) pour appliquer.");
        } catch (Exception e) {
            System.err.println("ERREUR : " + e.getMessage());
            System.exit(1);
        }
    }
}
