# ChunkDiff - conception

## Idee
Un chunk intact = regenerable depuis la seed -> on le supprime du .mca.
Un chunk peu modifie = on supprime aussi, et on stocke seulement le delta.

## Risques majeurs (a traiter AVANT toute publication stable)
1. **Changement de generateur entre versions MC** : mettre a jour MC change le terrain
   regenere, donc un chunk "supprime" reviendrait different. -> stocker la version MC de
   generation par region ; refuser/avertir si differente.
2. **Comparaison** : seulement blocs / block entities / entites. Lumiere, heightmaps,
   InhabitedTime, LastUpdate ignores.
3. **Structures/features debordant sur les chunks voisins** : deterministes par seed,
   mais le comparateur doit travailler sur des chunks au statut FULL.
4. **Ecriture des .mca** : Paper garde des handles ouverts. Ne modifier que des regions
   non chargees, ou appliquer un plan de suppression au prochain demarrage avant chargement.
5. **Securite des donnees** : dry-run par defaut, backup obligatoire, `min-age-days`, rien
   de supprime tant que le delta n'a pas passe une verification round-trip.
6. Datapacks de worldgen / generateurs custom : detecter et desactiver le plugin.

## Phases
- 0 Squelette, CI, publication Modrinth (ce depot)
- 1 Format .cdf + encodage/decodage delta + tests unitaires
- 2 Restauration (ChunkLoadEvent / populator) + test round-trip
- 3 Scanner (monde de reference) en dry-run : rapport d'espace gagnable
- 4 Compaction reelle avec verification round-trip + backup
- 5 Autres versions (1.21.x, 26.4...), Folia
