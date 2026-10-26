package dev.chunkdiff.scan;

/**
 * Compare chaque chunk du monde a sa version "seed pure" generee dans un monde
 * de reference temporaire (meme seed, meme type, memes dimensions).
 * Resultat : PRISTINE (supprimable), DELTA (petit diff), FULL (garder tel quel).
 * TODO phase 3.
 */
public final class PristineScanner {
    public enum Verdict { PRISTINE, DELTA, FULL }
}
