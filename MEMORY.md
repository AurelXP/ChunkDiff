# MEMOIRE DU PROJET (a coller dans une nouvelle conversation si besoin)
Projet : ChunkDiff, plugin Paper. Supprime les chunks identiques a la seed, stocke des deltas.
Cible 1 : Minecraft/Paper 26.3 (beta, Java 25). Puis autres versions.
Outils : GitHub (+Actions). Modrinth : REPORTE a beaucoup plus tard.
Etat : phase 0 OK (CI verte, jar 0.1.0 construit). Phase 1 en cours (codec + store). Rien de publie.
A faire ensuite : phase 1 (format .cdf), verifier coordonnees Maven paper-api 26.3.
Decisions : dry-run par defaut ; verification round-trip avant toute suppression.
Phase 1 : DeltaCodec (zstd), DeltaStore (.cdf par region, ecriture atomique), tests JUnit. A valider par la CI.
Phase 2 a venir : appliquer le delta (blocs + block entities + entites) dans RestoreListener.
