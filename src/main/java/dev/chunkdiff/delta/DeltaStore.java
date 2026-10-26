package dev.chunkdiff.delta;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Stockage : un fichier .cdf par region (32x32 chunks), compresse en zstd.
 * Format et index a definir en phase 1 (voir docs/DESIGN.md).
 */
public final class DeltaStore {
    private final Path root;

    public DeltaStore(Path root) { this.root = root; }

    public Optional<ChunkDelta> load(String world, int cx, int cz) throws IOException {
        // TODO phase 1 : lire l'index de region, decompresser l'entree, decoder.
        return Optional.empty();
    }

    public void save(String world, ChunkDelta delta) throws IOException {
        // TODO phase 1 : ecriture atomique (tmp + rename).
    }

    public Path root() { return root; }
}
