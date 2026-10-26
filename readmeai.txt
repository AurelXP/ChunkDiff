=====================================================================
README POUR IA - A LIRE EN PREMIER SI TU REPRENDS CE PROJET
=====================================================================

ROLE
Tu es le "big boss" du projet ChunkDiff (titre donne par l'utilisateur, ton joueur).
Tu diriges le developpement. L'utilisateur execute les etapes manuelles. Il parle francais :
reponds en francais.

LE PROJET
Plugin Paper pour serveur Minecraft Java qui libere de l'espace disque :
 - un chunk identique a ce que la seed genererait -> supprime des fichiers .mca
   (le serveur le regenere tout seul depuis la seed)
 - un chunk peu modifie -> supprime aussi, et on stocke uniquement le DELTA
   (blocs modifies, block entities, entites) dans nos propres fichiers .cdf.
   Quand le chunk est regenere, on rejoue le delta par-dessus.
Depot : https://github.com/AurelXP/ChunkDiff   (branche main)

CIBLES
 1. Minecraft / Paper 26.3 (Paper en beta, Java 25 obligatoire). API : paper-api
    26.3.build.143-beta (confirme valide par la CI).
 2. Ensuite d'autres versions (1.21.x, 26.4, Folia...). Pas avant que 26.3 marche.
 Modrinth : publication REPORTEE a beaucoup plus tard. Ne pas s'en occuper maintenant.

CONTRAINTES DE TRAVAIL (IMPORTANT)
 - L'UTILISATEUR UTILISE UN HEBERGEUR EN LIGNE : il n'a que le panel (start/stop/restart, console,
   gestionnaire de fichiers/FTP) et ses plugins. PAS de ligne de commande, pas de `java -jar` serveur
   eteint. Tout doit passer par des commandes in-game/console et par le redemarrage du serveur.
 - Tu n'as PAS acces direct a GitHub en ecriture, ni a Modrinth. Pas de compilateur Java
   dans le sandbox : tu ne peux pas compiler. La CI GitHub Actions est le juge.
 - L'utilisateur ne peut pas faire git push. Il cree/colle les fichiers via le site GitHub
   ("Add file > Create new file", en tapant le chemin complet dans le nom).
   -> Donne-lui toujours : (1) la liste exacte des fichiers avec leur chemin complet,
      (2) des etapes numerotees tres simples, (3) la verification (CI verte/rouge),
      (4) demande-lui de coller l'erreur si rouge.
   -> Erreur deja arrivee : il a mis des fichiers a la racine au lieu de src/. Insiste sur
      les chemins complets et verifie le depot avec web_fetch sur l'URL du depot.
 - Dans le sandbox bash, l'expansion d'accolades {a,b} NE MARCHE PAS (sh). Utilise bash
   explicite ou des boucles.
 - Ecris du code simple et conservateur : on ne peut pas compiler, donc evite les API
   Paper obscures. Prefere l'API Bukkit stable.

ARCHITECTURE (src/main/java/dev/chunkdiff/)
 ChunkDiffPlugin        point d'entree, enregistre listener + commande debug
 delta/ChunkDelta       record : chunkX, chunkZ, palette(List<String>), blocks, blockEntitiesNbt, entitiesNbt
 delta/DeltaCodec       encode/decode un delta (format version 1, zstd niveau 9)
 delta/DeltaStore       un fichier .cdf par region 32x32 : plugins/ChunkDiff/deltas/<monde>/r.<rx>.<rz>.cdf
                        ecriture atomique tmp+rename ; load/save/remove
 delta/RestoreListener  ChunkLoadEvent (isNewChunk) -> rejoue le delta
 scan/PristineScanner   (vide) comparera chaque chunk a sa version "seed pure"
 debug/DebugCommand     /chunkdiff testdelta | scan | plan | cancel
 scan/Plan              fichier plan (partage plugin / outil hors ligne)
 cli/RegionRewriter, cli/Compactor, cli/CompactMain (CLI optionnel, Main-Class du jar)
 ChunkDiffBootstrap     applique la compaction au demarrage, avant le chargement des mondes
 scan/RegionIndex       lit les en-tetes .mca (liste des chunks + taille sur disque) ; test RegionIndexTest
 Tests : src/test/java/dev/chunkdiff/delta/DeltaStoreTest (round-trip codec + store)
 Build : Gradle (CI utilise gradle 9.1.0 via setup-gradle), Java 25 toolchain, zstd-jni embarque.
 CI : .github/workflows/ci.yml (build + tests + upload du jar en artifact)

REGLES DE COMPARAISON (decidees)
 On compare seulement : etats de blocs, block entities, entites.
 On ignore : lumiere, heightmaps, InhabitedTime, LastUpdate.

RISQUES MAJEURS (voir docs/DESIGN.md)
 1. Changer de version MC change le terrain regenere -> stocker la version MC de generation,
    refuser d'agir si elle differe.
 2. Ecrire les .mca pendant que Paper tourne est dangereux -> operer sur regions non chargees
    ou appliquer un plan au demarrage suivant.
 3. Securite : dry-run par defaut, backup exige, verification round-trip avant toute suppression.
 4. Datapacks de worldgen / generateurs custom : detecter et desactiver.

ETAT D'AVANCEMENT
 [x] Phase 0  squelette + CI verte
 [x] Phase 1  DeltaCodec + DeltaStore + tests (CI verte)
 [x] Phase 2  restauration des BLOCS au chargement d'un chunk neuf. VALIDE en test reel
              (3 blocs de diamant apparus via /chunkdiff testdelta). Block entities/entites : phase 2b.
 [ ] Phase 2b block entities + entites (necessite NBT / API plus bas niveau)
 [x] Phase 3  scanner : /chunkdiff scan [monde] [n|all] (lecture seule), /chunkdiff cancel.
              CI verte. NON TESTE EN REEL (l'utilisateur ne peut pas lancer de serveur) : si une IA
              ou un testeur peut le faire, valider en priorite (rapport, dossier region, suppression
              du monde chunkdiff_ref). RegionIndex lit les en-tetes .mca (taille + timestamp).
              FULL d'office si : block entities, entites importantes (nommees, apprivoisees, villageois,
              non-mobs), chunk modifie depuis moins de min-age-days.
 [~] Phase 4  compaction en 2 temps, JAMAIS d'ecriture des .mca pendant que le serveur tourne :
              (a) EN JEU : /chunkdiff plan [monde] [n|all] = scan + ecrit les deltas (DELTA) dans
                  plugins/ChunkDiff/deltas et un plan plugins/ChunkDiff/plans/<monde>.plan
                  (liste des chunks + timestamp du scan) + mc-version.txt. Ne supprime rien.
              (b) /chunkdiff compact (affiche les consignes) puis /chunkdiff compact confirm : cree
                  plans/APPLY. L'utilisateur REDEMARRE depuis le panel. ChunkDiffBootstrap (PluginBootstrap
                  de Paper, declare dans paper-plugin.yml) s'execute AVANT le chargement des mondes,
                  appelle cli/Compactor : verifie session.lock, sauvegarde dans chunkdiff-backup-<date>/
                  (a cote de region/ ; attention quota disque hebergeur), reecrit les .mca de facon
                  compacte (cli/RegionRewriter), retire aussi les entites (entities/) des chunks retires,
                  NE TOUCHE PAS poi/. Un chunk dont le timestamp a change depuis le scan est GARDE.
                  Plan renomme en .plan.done apres traitement. /chunkdiff compact cancel annule.
                  (cli/CompactMain = outil en ligne de commande OPTIONNEL, inutilisable chez cet utilisateur.)
              Au demarrage suivant, les chunks sont regeneres par la seed et RestoreListener rejoue les deltas.
              Tests CI : RegionRewriterTest, PlanTest, RegionIndexTest. Bootstrapper et commandes NON TESTES
              sur un vrai serveur (verifier : API BootstrapContext#getLogger/getDataDirectory, ordre de demarrage).
              LIMITE MAJEURE : apres compaction le monde depend du generateur de la version MC courante.
              Mettre a jour Minecraft change le terrain des chunks supprimes (coupures). ChunkDiffPlugin
              logue une alerte si la version change. TODO phase 6 : commande de "decompaction"
              (regenerer + sauvegarder tous les chunks supprimes) a lancer AVANT toute mise a jour MC.
 [ ] Phase 5  autres versions, Folia

TEST MANUEL PHASE 3
 1. Serveur de test avec un monde deja explore (de preference genere sur Paper 26.3 : si le monde
    vient d'une autre version, beaucoup de chunks seront FULL, c'est normal et sur).
 2. /chunkdiff scan world 200   puis lire le rapport (console + chat).
 3. Verifier que le dossier region affiche est le bon, et que le monde chunkdiff_ref est supprime a la fin.
 4. Test de sens : poser des blocs a la main dans un chunk, rescanner : il doit passer en DELTA.

(ancien) TEST MANUEL PHASE 2 - VALIDE
 1. Mettre le jar (artifact de la CI) dans plugins/ d'un serveur Paper 26.3 de test (Java 25).
 2. Console/op : /chunkdiff testdelta   -> stocke 3 blocs de diamant dans le chunk (10000,10000).
 3. /tp @s 160000 310 160000 en creatif/vol : les blocs de diamant doivent apparaitre a
    y=300, x=160000..160002, z=160000.
 Si absents : lire les logs, demander l'erreur.

CONVENTIONS
 - Reponses courtes, etapes numerotees, un seul objectif a la fois.
 - Tenir ce fichier a jour a chaque phase (c'est la memoire du projet).
=====================================================================
