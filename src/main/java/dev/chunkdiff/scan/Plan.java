package dev.chunkdiff.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan de compaction : liste des chunks a supprimer des .mca (avec leur date de derniere
 * modification au moment du scan, pour detecter tout changement ulterieur).
 * Partage entre le plugin (ecriture) et l'outil hors ligne (lecture). Aucune dependance Bukkit.
 *
 * Format texte :
 *   chunkdiff-plan 1 / mc <version> / seed <n> / world <nom> / region <chemin> / chunk <cx> <cz> <ts>
 */
public record Plan(String mc, long seed, String world, String regionDir, List<Entry> chunks) {

    public record Entry(int cx, int cz, int ts) {}

    public static void write(Path file, Plan p) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("chunkdiff-plan 1\n");
        sb.append("mc ").append(p.mc()).append('\n');
        sb.append("seed ").append(p.seed()).append('\n');
        sb.append("world ").append(p.world()).append('\n');
        sb.append("region ").append(p.regionDir()).append('\n');
        for (Entry e : p.chunks()) sb.append("chunk ").append(e.cx()).append(' ').append(e.cz()).append(' ').append(e.ts()).append('\n');
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString());
    }

    public static Plan read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || !lines.get(0).trim().equals("chunkdiff-plan 1"))
            throw new IOException("fichier plan invalide : " + file);
        String mc = "", world = "", region = "";
        long seed = 0;
        List<Entry> chunks = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank()) continue;
            if (l.startsWith("mc ")) mc = l.substring(3).trim();
            else if (l.startsWith("seed ")) seed = Long.parseLong(l.substring(5).trim());
            else if (l.startsWith("world ")) world = l.substring(6);
            else if (l.startsWith("region ")) region = l.substring(7);
            else if (l.startsWith("chunk ")) {
                String[] t = l.substring(6).trim().split(" ");
                if (t.length != 3) throw new IOException("ligne invalide : " + l);
                chunks.add(new Entry(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2])));
            } else throw new IOException("ligne inconnue dans le plan : " + l);
        }
        return new Plan(mc, seed, world, region, chunks);
    }
}
