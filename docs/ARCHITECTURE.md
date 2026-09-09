# Architecture

## Vue d'ensemble

```
Claude Code / client MCP
   |  JSON-RPC MCP sur stdio (ou HTTP streamable)
   v
mcp-server (Node)            src/index.ts   : enregistre chaque tool comme relais vers le bridge
                              src/tools.ts   : catalogue (nom MCP -> methode RPC + schema zod)
                              src/bridge.ts  : client HTTP du bridge, erreurs typees
                              src/config.ts  : env + lecture du jeton dans la config du mod
   |  POST /rpc {method, params}  ->  {ok, result | error}
   |  GET  /events (SSE)          <-  evenements de jeu
   v
mod Fabric "mcbridge" (client Minecraft, Java 25)
   McBridgeMod                 point d'entree : config, routeur, bus, bridge, handlers
   bridge/                     HttpBridgeServer, RpcRouter, RpcContext, RpcException, MainThread, SseHub, Json
   config/BridgeConfig         config/mcbridge.json : reseau, jeton, delais, portes, screenshot, reflexion
   events/                     EventBus (tampon + SSE), ChatLog, ClientEvents (Fabric API -> bus)
   util/                       ClientMc (thread de rendu), TickWaiter, EntityJson, Images, CaptureState, GuiCursor
   handlers/                   un fichier par groupe : Info, Player, Camera, Vision, World, Chat,
                               Event, ClientOption, Resource, Log, Game
   focus/                      FocusState (regles + snapshot), FocusHandlers
   studio/                     StudioHandlers (frameTarget, bounds), StudioBounds, StudioAngles
   gui/                        GuiHandlers : etat, ouverture, survol, infobulle, capture decoupee, clic
   refs/                       RefStore (image + recette sur disque), RefHandlers (rejeu et comparaison)
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
5. `index.ts` transforme le resultat : bloc `image` si `kind:"image"`, sinon texte JSON et
   `structuredContent`. Une erreur du bridge devient un resultat `isError:true` lisible par l'IA.

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

## Securite

- Ecoute locale, jeton bearer, comparaison en temps constant, refus des adresses non locales.
- Portes `enableXxx` dans la config, verifiees dans les handlers et visibles dans `info.status`.
- Reflexion limitee par listes de packages ; les classes d'execution de processus, de fichiers et
  de reseau sont bloquees par defaut.

## Provenance

Repris de mcpfabric (MIT) : la forme du bridge HTTP, du routeur, du passage au thread principal,
du bus SSE et du catalogue declaratif. Repris de minecraft-mcp (MIT) : le systeme de reflexion et
la configuration de build 26.1.2. Tout a ete reecrit et adapte ; les deux depots sont dans
`reference/` pour comparaison.
