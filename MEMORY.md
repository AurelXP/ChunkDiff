# MEMOIRE DU PROJET (a coller dans une nouvelle conversation si besoin)
Projet : ChunkDiff, plugin Paper. Supprime les chunks identiques a la seed, stocke des deltas.
Cible 1 : Minecraft/Paper 26.3 (beta, Java 25). Puis autres versions.
Outils : GitHub (+Actions), Modrinth (publication).
Etat : phase 0 faite (squelette + CI + release workflow). Rien de publie.
A faire ensuite : phase 1 (format .cdf), verifier coordonnees Maven paper-api 26.3.
Decisions : dry-run par defaut ; verification round-trip avant toute suppression.
Secrets GitHub requis : MODRINTH_TOKEN ; variable : MODRINTH_PROJECT_ID.
