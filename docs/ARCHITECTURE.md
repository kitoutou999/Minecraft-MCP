# Architecture

## Vue d'ensemble

```
Claude Code / client MCP
   |  JSON-RPC MCP sur stdio (ou HTTP streamable)
   v
mcp-server (Node)            src/index.ts   : enregistre chaque tool comme relais vers le bridge
                              src/tools.ts   : catalogue (nom MCP -> methode RPC + schema zod)
                              src/results.ts : mise en forme des reponses (JSON compact, flottants
                                               arrondis, blocs image)
                              src/steps.ts   : run_steps, plusieurs tools en un seul appel
                              src/bridge.ts  : client HTTP du bridge, erreurs typees
                              src/config.ts  : env + lecture du jeton dans la config du mod
   |  POST /rpc {method, params}  ->  {ok, result | error}
   |  GET  /events (SSE)          <-  evenements de jeu
   v
mod Fabric "mcbridge" (client Minecraft, Java 25)
   McBridgeMod                 point d'entree : config, routeur, bus, bridge, handlers
   bridge/                     HttpBridgeServer, RpcRouter (verrou d'exclusivite), RpcContext,
                               RpcException, MainThread, SseHub, Json
   config/BridgeConfig         config/mcbridge.json : reseau, jeton, delais, portes, RCON, screenshot, reflexion
   events/                     EventBus (tampon + SSE), ChatLog, ClientEvents (Fabric API -> bus)
   util/                       ClientMc (thread de rendu), TickWaiter, EntityJson, Images, CaptureState,
                               GuiCursor, Commands (canaux d'envoi), Spectator, Excursion (sortie et retour),
                               Rcon (connexion), RconCodec (format des paquets)
   handlers/                   un fichier par groupe : Info, Player, Camera, Vision, World, Chat,
                               Event, ClientOption, Resource, Log, Game
   focus/                      FocusState (regles + snapshot), FocusHandlers
   studio/                     StudioHandlers (frameTarget, bounds), StudioBounds, StudioAngles,
                               Framing (geometrie de cadrage, pure), StudioResult (resume du resultat, pur)
   gui/                        GuiHandlers : etat, ouverture, survol, infobulle, capture decoupee, clic
   refs/                       RefStore (image + recette sur disque), RefHandlers (rejeu et comparaison)
   server/                     ServerHandlers (RCON), CommandGuard (garde-fou sur les commandes console)
   mixin/                      un mixin par point d'accroche, sans logique : EntityRenderDispatcher (entites),
                               Screen (ecran masque pendant capture), LevelRenderer (ciel, nuages, meteo, block
                               entities, couleur de fond), ChunkSectionsToRender (terrain), ParticleEngine,
                               FogRenderer, ScreenEffectRenderer, Camera, RenderSectionRegion (region)
                               MouseHandler (curseur virtuel), AbstractContainerScreen (accesseur)
   mixin/compat/               @Pseudo pour mods optionnels : Sodium (LevelSlice, SodiumWorldRenderer), Voxy
   reflect/                    ReflectionHandlers, ObjectRegistry, Serializer, PackageFilter
```

## Cycle d'un appel

1. Le client MCP appelle le tool `screenshot`. `index.ts` valide les arguments avec le schema zod
   du catalogue, applique `mapArgs` si present, et envoie `POST /rpc` avec
   `{method:"vision.screenshot", params}` et le jeton bearer.
2. `HttpBridgeServer` (thread HTTP du JDK) verifie l'adresse locale et le jeton, puis appelle
   `RpcRouter.dispatch`.
3. Le handler s'execute sur le thread HTTP. Pour toucher au jeu, il planifie une tache sur le
   thread de rendu avec `ClientMc.call(() -> ...)` et attend le resultat (timeout
   `callTimeoutMs`). Les operations longues (screenshot GPU, rechargement) recuperent un future et
   l'attendent avec `MainThread.await` hors du thread de rendu.
4. Le routeur enveloppe le resultat (`{ok:true,result}`) ou l'erreur (`{ok:false,error}`), jamais
   d'exception vers HTTP.
5. `results.ts` transforme le resultat : bloc `image` si `kind:"image"`, sinon texte JSON compact
   avec les flottants arrondis, plus `structuredContent`. Une erreur du bridge devient un resultat
   `isError:true` lisible par l'IA.
6. `run_steps` (`steps.ts`) repete ce cycle pour chaque etape d'une liste, cote serveur MCP, apres
   avoir valide toutes les etapes avec le schema de leur tool, et renvoie tout en une reponse.

## Cout en tokens

Chaque appel MCP est un tour de conversation, et chaque tour relit tout le contexte accumule : un
tour a 400 K tokens de contexte coute plus que n'importe quel resultat. Ce qui en decoule ici :

- **Un aller-retour de moins vaut plus qu'un resultat plus court.** `frame_target` fait cadrage,
  placement, fond, capture et restauration en un appel ; `run_steps` enchaine n'importe quelle
  suite d'appels dependants.
- **Le texte d'un resultat est relu a chaque tour suivant.** `results.ts` serialise sans indentation
  et arrondit les flottants (trois decimales a partir de 1, quatre chiffres significatifs en deca) :
  moitie moins de texte sur un resultat de cadrage, sans perte utile.
- **Une image coute largeur x hauteur / 750 tokens**, plafonne a 1 568 px de grand cote ; le format
  et la qualite JPEG n'y changent rien. Une image n'est jamais repetee en texte, et une serie
  (`capture_animation`) part en planche unique. L'image des differences d'une reference est
  recadree sur la zone touchee et reduite, et omise quand la difference n'est que du bruit.
- **Le detail sur demande.** `frame_target` renvoie un resume (`studio/StudioResult`) et garde le
  plan complet derriere `verbose` ; un handler qui produit beaucoup de metadonnees suit le meme
  modele plutot que de tout renvoyer par defaut.

## Actions exclusives

Une methode enregistree par `registerExclusive` prend le controle du client : elle deplace la
camera, change le mode de jeu, masque le decor, force le champ de vision, puis restaure tout. Deux
appels de ce genre en parallele se marcheraient dessus, et la restauration du premier effacerait le
reglage du second : l'image renvoyee ne correspondrait alors a aucune demande. Le routeur les
serialise avec un verrou unique, et refuse en `busy` au-dela de `busyTimeoutMs`.

Ce cas n'arrive jamais avec un client MCP unique en stdio, mais rien ne l'empeche des que deux
clients sont branches sur le meme jeu, ou avec le transport HTTP qui accepte plusieurs sessions.
Les lectures ne prennent pas ce verrou : l'etat du bridge reste consultable pendant une prise de
vue. Liste des methodes concernees dans `docs/PROTOCOL.md`.

## Sortie et retour : util/Excursion

Photographier demande toujours la meme sequence : relever ou est le joueur et dans quel mode, passer
en spectateur pour qu'il ne tombe pas, forcer un champ de vision reproductible, isoler le sujet,
prendre la vue, tout remettre en place. `Excursion` porte cette sequence une fois pour toutes, en
try-with-resources ; `vision.screenshot`, `studio.frameTarget` et `refs.*` ne declarent plus que ce
qu'ils veulent en garder. La fermeture ne leve jamais et tente chaque etape independamment.

Une regle de securite y est inscrite : le mode de jeu n'est rendu que si la position l'a ete. Rendre
la survie a un joueur reste a la position de la camera, souvent en l'air, le ferait tomber.
`refs.compareAll` est la seule exception, parce que chaque comparaison ramene deja le joueur d'ou
elle l'a pris ; elle le demande explicitement.

## Threads

| Thread | Ce qui y tourne |
|---|---|
| HTTP (`mcbridge-http-N`, daemon) | reception, auth, dispatch, encodage d'images, lecture de fichiers, reflexion (preparation) |
| Rendu (thread principal Minecraft) | toute lecture ou ecriture de l'etat du jeu, `Screenshot.takeScreenshot`, `reloadResourcePacks`, invocation par reflexion |
| Callback GPU | `Screenshot.takeScreenshot` livre la `NativeImage` sur le thread de rendu apres lecture asynchrone |

Regle : un handler ne bloque jamais le thread de rendu sur un future. Voir `ResourceHandlers`.

## Evenements

`EventBus` garde les 2000 derniers evenements avec un id croissant et les diffuse en SSE.
Sources actuelles (`ClientEvents`) : `chat`, `system_message`, `join`, `disconnect` via Fabric
API ; `focus_changed` et `resources_reloaded` emis par les handlers. `events.poll` avec `sinceId`
permet une lecture incrementale sans SSE.

## Mode focus

`FocusState` est un singleton a champs volatils, ecrit par `FocusHandlers` (threads HTTP) et lu par
les mixins (thread de rendu, threads de compilation des sections). Trois volets cumulables :
selection d'entites (regles dans `shouldRender`, racines recalculees une fois par tick), region
(blocs hors boite vus comme de l'air par les compilateurs vanilla et Sodium, reconstruction via
`allChanged()`), scene (terrain, ciel, particules, block entities, brouillard, clipping, overlay,
couleur de fond). Les mixins ne contiennent aucune logique : ils lisent un booleen ou appellent
`FocusState`. Les mixins qui partagent une cible avec Sodium, Iris ou Voxy ont la priorite 2000
pour etre appliques apres eux ; les mixins sur des classes de mods optionnels sont `@Pseudo`.
Detail des points d'accroche dans `docs/ROADMAP.md`, lot 2.

## Reflexion

`ReflectionHandlers` resout classe, methode ou champ sur le thread HTTP (filtre de packages,
compatibilite des types avec boxing), puis invoque sur le thread de rendu. `Serializer` transforme
les types connus en JSON et enregistre le reste dans `ObjectRegistry` sous forme de poignees
`obj_N` ; `assignTo` cree une variable `$nom`. Les poignees les plus anciennes sont evincees
au-dela de `maxObjectRefs`.

## Captures d'interface

Le survol d'une case passe par un curseur virtuel : `MouseHandlerMixin` detourne
`getScaledXPos`/`getScaledYPos`, les deux lectures dont `GameRenderer.extractGui` se sert pour dire
a l'ecran ouvert ou se trouve la souris. L'ecran en deduit la case survolee et affiche son
infobulle, sans que la souris reelle du joueur bouge. Les coordonnees des cases viennent de
`AbstractContainerScreenAccessor` (`leftPos`, `topPos`, `menu`), en unites d'interface ; le
framebuffer etant en pixels physiques, le decoupage les multiplie par `guiScale`. Un decoupage
etroit est agrandi d'un facteur entier au plus proche voisin, qui garde les textures nettes.

## References visuelles

Une reference est un couple image et recette. La recette decrit comment reproduire exactement la
prise de vue : position et orientation de camera, champ de vision, options de scene, resolution.
Sans elle, une comparaison pixel a pixel n'aurait aucun sens, puisque le moindre deplacement change
toute l'image. `refs.compare` rejoue la recette, puis `Images.diff` compte les pixels dont l'ecart
depasse la tolerance et delimite la zone touchee. Si la fenetre a change de taille entre les deux,
la nouvelle capture est ramenee a la taille de la reference et le resultat le signale.

## Tests

`cd mod && ./gradlew test` et `cd mcp-server && npm test` s'executent sans Minecraft ni serveur, en
quelques secondes. Ils couvrent ce qui est calculable hors du jeu, c'est-a-dire la ou une erreur ne
se verrait qu'a l'oeil sur une image : geometrie du cadrage (`Framing`), mesure du sujet et
comparaison d'images (`Images`), format des paquets RCON (`RconCodec`), garde-fou sur les commandes
console (`CommandGuard`), filtre de la reflexion (`PackageFilter`), serialisation des actions
(`RpcRouter`), decouverte du jeton et erreurs du client HTTP, catalogue des tools.

`node scripts/check-contract.mjs` rapproche les deux moities du contrat : toute methode enregistree
en Java a un tool, tout tool vise une methode qui existe, chaque parametre porte une description, et
aucune action exclusive ne s'annonce `readOnlyHint`. Rien dans le langage ne relie ces deux
fichiers, donc une methode renommee d'un cote ne se verrait qu'a l'execution.

Ce qui demande un joueur en jeu (rendu, mixins, captures) n'est pas automatisable ici et se verifie
a la main : `installMod`, relance du client, puis `scripts/rpc.sh info.status`.

## Securite

- Ecoute locale, jeton bearer, comparaison en temps constant, refus des adresses non locales.
- Portes `enableXxx` dans la config, verifiees dans les handlers et visibles dans `info.status`.
- Reflexion limitee par listes de packages ; les classes d'execution de processus, de fichiers et
  de reseau sont bloquees par defaut. Une poignee d'objet n'est creee que pour une classe autorisee :
  sans cela, une methode autorisee renvoyant un objet interdit donnerait prise sur lui.
- RCON facultatif, desactive par defaut. Le protocole n'est pas chiffre : liaison locale ou tunnel.
  `CommandGuard` refuse les commandes de `blockedCommands` y compris derriere un `execute ... run`,
  ce qui evite l'accident sans pretendre resister a l'intention : le mot de passe RCON donne deja
  tous les droits.

## Provenance

Repris de mcpfabric (MIT) : la forme du bridge HTTP, du routeur, du passage au thread principal,
du bus SSE et du catalogue declaratif. Repris de minecraft-mcp (MIT) : le systeme de reflexion et
la configuration de build 26.1.2. Tout a ete reecrit et adapte ; les deux depots sont dans
`reference/` pour comparaison.
