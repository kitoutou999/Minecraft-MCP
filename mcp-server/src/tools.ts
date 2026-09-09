/**
 * Catalogue des tools MCP : la source de verite du contrat cote TypeScript.
 *
 * Chaque entree relie un tool MCP (snake_case, visible par le modele) a une methode RPC du mod
 * (namespace.action) avec son schema zod. `index.ts` enregistre chaque entree de facon generique.
 * Pour ajouter un tool : ajouter le handler Java, puis une entree ici, puis documenter dans
 * docs/TOOLS.md. Garder les descriptions precises : c'est ce que lit l'IA pour choisir un tool.
 */
import { z } from "zod";

export interface ToolDef {
  /** Nom du tool MCP expose au modele. */
  name: string;
  /** Methode RPC du bridge (voir docs/PROTOCOL.md). */
  method: string;
  /** Titre court. */
  title: string;
  /** Description lue par le modele : comportement, prerequis, effets de bord. */
  description: string;
  /** Schema zod des arguments (raw shape). */
  inputSchema: z.ZodRawShape;
  /** Indices MCP pour le client. */
  annotations?: {
    readOnlyHint?: boolean;
    destructiveHint?: boolean;
    idempotentHint?: boolean;
    openWorldHint?: boolean;
  };
  /**
   * "json" (defaut) : texte + structuredContent ; "image" : un bloc image depuis result.base64 ;
   * "images" : un bloc image par entree de result.shots[], ou une image unique si result.base64 existe ;
   * "diff" : mesures de comparaison, avec l'image des differences quand il y en a.
   */
  kind?: "json" | "image" | "images" | "diff";
  /** Transformation optionnelle des arguments avant l'envoi au bridge. */
  mapArgs?: (args: Record<string, unknown>) => Record<string, unknown>;
}

const READ = { readOnlyHint: true } as const;
const WRITE = { destructiveHint: false } as const;

const vec3 = () => ({
  x: z.number().describe("Coordonnee X (est/ouest)."),
  y: z.number().describe("Coordonnee Y (hauteur)."),
  z: z.number().describe("Coordonnee Z (nord/sud)."),
});

const reflectArg = z.object({
  type: z.string().describe('Type Java : "int", "double", "boolean", "java.lang.String", "net.minecraft.core.BlockPos", ou une classe autorisee.'),
  value: z.unknown().describe('Valeur. Une chaine "$nom" ou "obj_N" designe une variable ou une poignee d\'objet.'),
});

export const TOOLS: ToolDef[] = [
  // ===== etat ==================================================================================
  {
    name: "get_status",
    method: "info.status",
    title: "Etat du bridge et du client",
    description:
      "A appeler en premier. Renvoie la version du mod et de Minecraft, si le joueur est dans un monde, le serveur rejoint, " +
      "les capacites activees dans la config (vision, commands, clientOptions, focus, reflection), le tick courant et la liste des methodes RPC.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_player",
    method: "player.getState",
    title: "Etat du joueur local",
    description:
      "Position, position des yeux, yaw/pitch, dimension, mode de jeu, vie, faim, vol, spectateur, accroupi, sprint. " +
      "Convention : yaw 0 = sud, 90 = ouest, -90 = est, 180 = nord ; pitch -90 = haut, 90 = bas.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== camera ================================================================================
  {
    name: "get_camera",
    method: "camera.get",
    title: "Position de la camera",
    description: "Position et orientation reelles de la camera de rendu (differentes du joueur en 3e personne), plus celles du joueur.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "look",
    method: "camera.look",
    title: "Orienter la vue",
    description:
      "Oriente la vue du joueur. Donner yaw/pitch absolus ou deltaYaw/deltaPitch relatifs. Le corps suit la tete. " +
      "Pour deplacer le joueur, utiliser send_command avec /tp (ou le parametre teleport de screenshot).",
    inputSchema: {
      yaw: z.number().optional().describe("Yaw absolu en degres."),
      pitch: z.number().min(-90).max(90).optional().describe("Pitch absolu en degres."),
      deltaYaw: z.number().optional().describe("Rotation horizontale relative."),
      deltaPitch: z.number().optional().describe("Rotation verticale relative."),
    },
    annotations: WRITE,
  },
  {
    name: "look_at",
    method: "camera.lookAt",
    title: "Regarder un point",
    description: "Oriente la vue du joueur vers un point du monde (calcule yaw/pitch depuis la position des yeux).",
    inputSchema: { ...vec3() },
    annotations: WRITE,
  },

  // ===== vision ================================================================================
  {
    name: "screenshot",
    method: "vision.screenshot",
    title: "Capturer l'ecran",
    description:
      "Capture le rendu du client et renvoie une image (JPEG par defaut, redimensionnee a maxWidth). " +
      "Options : teleport {x,y,z,yaw?,pitch?} deplace le joueur avant la capture, en spectateur par defaut pour qu'il ne tombe pas ; " +
      "look {yaw,pitch} oriente la vue ; hideHud (defaut true) masque l'interface ; waitTicks (defaut 2, ou 10 apres teleport) " +
      "laisse le temps aux chunks et modeles de charger, 20 ticks = 1 s. Le HUD est restaure apres la capture. " +
      "Un ecran ouvert (chat, menu Echap, inventaire) est masque par defaut le temps de la capture sans etre ferme (hideScreen) ; la reponse indique screenOpen et screenHidden. " +
      "Utiliser format 'png' et maxWidth 0 pour une image fidele en pleine resolution (couteuse en tokens).",
    inputSchema: {
      teleport: z
        .object({ ...vec3(), yaw: z.number().optional(), pitch: z.number().optional() })
        .optional()
        .describe("Deplacer le joueur avant la capture. Les coordonnees designent ses pieds, comme une commande de teleportation."),
      stabilize: z
        .boolean()
        .optional()
        .default(true)
        .describe(
          "Passer en spectateur avant de deplacer le joueur, pour qu'il ne tombe pas si la position est en l'air, et pour qu'aucune " +
            "collision ni modele de joueur ne gene la capture. Sans lui, une position en hauteur fait chuter un joueur en survie. " +
            "Le mode precedent n'est rendu que si returnToStart est vrai : le rendre en plein vol ferait tomber le joueur.",
        ),
      returnToStart: z
        .boolean()
        .optional()
        .default(false)
        .describe("Revenir a la position et au mode de jeu du depart apres la capture. Sinon le joueur reste sur place."),
      look: z
        .object({ yaw: z.number().optional(), pitch: z.number().optional() })
        .optional()
        .describe("Orienter la vue avant la capture."),
      hideHud: z.boolean().optional().default(true).describe("Masquer le HUD pendant la capture."),
      hideScreen: z.boolean().optional().default(true).describe("Ne pas dessiner l'ecran ouvert (chat, menu Echap, inventaire, menu serveur) pendant la capture, sans le fermer ni rendre la souris au jeu."),
      closeScreen: z.boolean().optional().default(false).describe("Fermer reellement l'ecran ouvert avant la capture (perd un inventaire ou un menu serveur ; rend la souris au jeu). Rarement utile, preferer hideScreen."),
      waitTicks: z.number().int().min(1).max(600).optional().describe("Ticks a attendre avant la capture."),
      maxWidth: z.number().int().min(0).max(8192).optional().describe("Largeur max de l'image renvoyee (0 = native). Defaut : config du mod (1280)."),
      format: z.enum(["jpeg", "png"]).optional().describe("Format de sortie. Defaut : config du mod (jpeg)."),
      quality: z.number().min(0.05).max(1).optional().describe("Qualite JPEG (defaut 0.85)."),
    },
    annotations: READ,
    kind: "image",
  },
  {
    name: "describe_scene",
    method: "vision.describeScene",
    title: "Decrire la scene",
    description:
      "Description textuelle de ce que vise le joueur (bloc avec face, ou entite) et des entites proches triees par distance, " +
      "avec leur type, nom, position et si ce sont des entites display (ModelEngine, Nexo). Moins couteux qu'un screenshot.",
    inputSchema: {
      radius: z.number().min(1).max(128).optional().default(32).describe("Rayon de recherche des entites."),
      maxEntities: z.number().int().min(1).max(500).optional().default(50),
    },
    annotations: READ,
  },

  {
    name: "capture_animation",
    method: "vision.burst",
    title: "Capturer un mouvement",
    description:
      "Prend une serie de captures espacees dans le temps, depuis le point de vue actuel. Une image seule ne dit rien d'une animation " +
      "ModelEngine, d'un effet de particules, d'un sort MythicMobs ou d'une transition de HUD : cette serie montre le mouvement. " +
      "Par defaut les images sont assemblees en une planche unique, lue de gauche a droite puis de haut en bas, ce qui coute une seule " +
      "image au lieu de plusieurs. Passer layout 'frames' quand le detail de chaque instant compte plus que le nombre de vues. " +
      "Se placer d'abord (send_command tp, look_at), et masquer le decor avec focus_scene si le sujet doit ressortir.",
    inputSchema: {
      frames: z.number().int().min(2).max(24).optional().default(6).describe("Nombre de captures."),
      intervalTicks: z.number().int().min(1).max(40).optional().default(4).describe("Ecart entre deux captures, 20 ticks = 1 s."),
      layout: z.enum(["sheet", "frames"]).optional().default("sheet").describe("'sheet' une planche unique, 'frames' une image par instant."),
      cellWidth: z.number().int().min(64).max(1024).optional().default(320).describe("Largeur d'une vignette de la planche."),
      columns: z.number().int().min(0).max(8).optional().default(0).describe("Colonnes de la planche (0 = grille la plus carree)."),
      maxWidth: z.number().int().min(0).max(4096).optional().describe("Largeur de chaque image en mode 'frames'."),
      hideHud: z.boolean().optional().default(true),
      hideScreen: z.boolean().optional().default(true),
      format: z.enum(["jpeg", "png"]).optional().default("jpeg"),
      quality: z.number().min(0.05).max(1).optional(),
    },
    annotations: READ,
    kind: "images",
  },

  // ===== references visuelles ==================================================================
  {
    name: "save_reference",
    method: "refs.save",
    title: "Enregistrer une image de reference",
    description:
      "Capture une image et la range sous un nom, avec la recette exacte qui l'a produite : position et orientation de camera, " +
      "champ de vision, options de scene. C'est la recette qui rend la comparaison possible plus tard. " +
      "Mode 'world' (defaut) : la camera est celle du joueur au moment de l'appel, sauf si camera est fourni ; entierement reproductible. " +
      "Mode 'gui' : le panneau de l'interface ouverte ; la comparaison exigera que le meme menu soit ouvert, ce que le mod ne peut pas " +
      "provoquer pour un menu de plugin. A poser une fois sur chaque element a surveiller, avant de toucher au pack. " +
      "Pour une reference fiable, isoler le sujet avec focus et masquer le decor avec scene : une scene vivante bouge d'une capture a l'autre.",
    inputSchema: {
      name: z.string().describe("Nom court, lettres chiffres point tiret souligne."),
      mode: z.enum(["world", "gui"]).optional().default("world"),
      note: z.string().optional().describe("A quoi sert cette reference, pour s'y retrouver plus tard."),
      camera: z
        .object({ x: z.number(), y: z.number(), z: z.number(), yaw: z.number().optional(), pitch: z.number().optional() })
        .optional()
        .describe("Position des yeux de la camera. Par defaut celle du joueur."),
      fov: z.number().int().min(20).max(110).optional(),
      scene: z
        .object({
          hideTerrain: z.boolean().optional(),
          hideSky: z.boolean().optional(),
          hideParticles: z.boolean().optional(),
          hideBlockEntities: z.boolean().optional(),
          disableFog: z.boolean().optional(),
          flatLighting: z.boolean().optional(),
          backgroundColor: z.string().optional(),
        })
        .optional()
        .describe("Options de scene a appliquer, memorisees dans la recette. Un fond uni et un eclairage plat rendent la comparaison plus stable."),
      focus: z
        .object({
          uuids: z.array(z.string()).optional(),
          types: z.array(z.string()).optional(),
          attachRadius: z.number().min(0).max(32).optional(),
          hideOthers: z.boolean().optional(),
          hideSelf: z.boolean().optional(),
        })
        .optional()
        .describe(
          "Entites a garder au rendu, memorisees dans la recette. Fortement conseille : sans isolation, un joueur qui passe dans le champ " +
            "ou une entite qui bouge suffit a faire diverger la comparaison (environ 0,2 % de pixels de bruit mesures sur une scene vivante).",
        ),
      hideHud: z.boolean().optional().default(true),
      waitTicks: z.number().int().min(1).max(200).optional().default(6),
      overwrite: z.boolean().optional().default(false),
    },
    annotations: WRITE,
  },
  {
    name: "compare_reference",
    method: "refs.compare",
    title: "Comparer une reference",
    description:
      "Rejoue la recette d'une reference et compare le resultat a l'image enregistree, pixel par pixel. Renvoie la part de pixels " +
      "differents, l'ecart maximal, la zone touchee, et une image ou les differences ressortent en magenta. " +
      "Sert a repondre a 'est-ce que ma modification a casse ce modele'.",
    inputSchema: {
      name: z.string(),
      tolerance: z.number().int().min(0).max(255).optional().default(8).describe("Ecart tolere par canal avant de compter un pixel comme different."),
      includeDiffImage: z.boolean().optional().default(true),
    },
    annotations: READ,
    kind: "diff",
  },
  {
    name: "compare_all_references",
    method: "refs.compareAll",
    title: "Verifier toutes les references",
    description:
      "Rejoue toutes les references et renvoie un verdict chiffre pour chacune, sans image : c'est l'appel a faire apres " +
      "reload_resource_pack pour savoir d'un coup ce qui a change visuellement. Relancer compare_reference sur celles qui ont bouge " +
      "pour voir la difference. Chaque reference en mode 'world' deplace brievement le joueur puis le remet en place.",
    inputSchema: {
      tolerance: z.number().int().min(0).max(255).optional().default(8),
      changedThresholdPercent: z
        .number()
        .min(0)
        .max(100)
        .optional()
        .default(0.5)
        .describe(
          "Part de pixels differents a partir de laquelle une reference est declaree modifiee. Mesures sur une scene vivante : environ " +
            "0,2 % de bruit sans rien changer, contre 3 % pour une vraie difference. Baisser le seuil si les references isolent bien leur sujet.",
        ),
      includeDiffImages: z.boolean().optional().default(false).describe("Joindre l'image des differences pour chaque reference modifiee. Couteux."),
    },
    annotations: READ,
  },
  {
    name: "list_references",
    method: "refs.list",
    title: "Lister les references",
    description: "Noms, mode, note et resolution des references enregistrees, avec le dossier ou elles sont rangees.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "delete_reference",
    method: "refs.delete",
    title: "Supprimer une reference",
    description: "Supprime l'image et la recette d'une reference.",
    inputSchema: { name: z.string() },
    annotations: { destructiveHint: true },
  },

  // ===== monde =================================================================================
  {
    name: "get_block",
    method: "world.getBlock",
    title: "Lire un bloc",
    description: "Identifiant et proprietes du bloc a une position entiere, tel que le client le connait (chunk charge requis).",
    inputSchema: {
      x: z.number().int(),
      y: z.number().int(),
      z: z.number().int(),
    },
    annotations: READ,
  },
  {
    name: "list_entities",
    method: "world.entitiesNearby",
    title: "Lister les entites proches",
    description:
      "Entites chargees dans un rayon autour du joueur (ou d'un centre donne), triees par distance. Filtres : types " +
      "(ex. ['zombie','minecraft:item_display']), includeDisplays, includePlayers, includeSelf. Sert a trouver l'UUID " +
      "ou l'id d'une cible pour focus_entities et get_entity.",
    inputSchema: {
      radius: z.number().min(1).max(256).optional().default(32),
      center: z.object(vec3()).optional().describe("Centre de recherche (defaut : le joueur)."),
      types: z.array(z.string()).optional().describe("Types a garder, avec ou sans prefixe minecraft:."),
      includeDisplays: z.boolean().optional().default(true),
      includePlayers: z.boolean().optional().default(true),
      includeSelf: z.boolean().optional().default(false),
      max: z.number().int().min(1).max(1000).optional().default(200),
    },
    annotations: READ,
  },
  {
    name: "get_entity",
    method: "world.getEntity",
    title: "Detail d'une entite",
    description:
      "Detail d'une entite par uuid ou id : boite englobante (pour cadrer la camera), passagers, et entites display situees " +
      "a moins de attachRadius (les os d'un modele ModelEngine, les parties d'un meuble Nexo).",
    inputSchema: {
      uuid: z.string().optional(),
      id: z.number().int().optional().describe("Id reseau de l'entite (change a chaque session)."),
      attachRadius: z.number().min(0).max(32).optional().default(4),
    },
    annotations: READ,
  },

  // ===== chat et commandes =====================================================================
  {
    name: "send_command",
    method: "chat.send",
    title: "Executer une commande",
    description:
      "Execute une commande en tant que joueur (sans le slash initial, il est ajoute). Le joueur doit avoir la permission sur le serveur. " +
      "Exemples : 'tp @s 100 80 100', 'gamemode spectator', 'time set noon', 'mm mobs spawn boss 1'. " +
      "La sortie de la commande arrive dans le chat : lire get_chat ou poll_events ensuite. " +
      "vanilla:true force l'implementation vanilla en prefixant 'minecraft:', indispensable quand un plugin redefinit la commande " +
      "(sur un serveur avec EssentialsX, /tp, /gamemode, /time ou /weather ont une autre syntaxe et rejettent les selecteurs comme @s).",
    inputSchema: {
      command: z.string().describe("Commande sans le slash initial."),
      vanilla: z.boolean().optional().default(false).describe("Prefixer 'minecraft:' pour viser la commande vanilla plutot que celle d'un plugin."),
    },
    annotations: WRITE,
    mapArgs: (a) => ({
      message: "/" + String(a.command ?? "").replace(/^\/+/, ""),
      vanilla: a.vanilla === true,
    }),
  },
  {
    name: "send_chat",
    method: "chat.send",
    title: "Envoyer un message",
    description: "Envoie un message de chat en tant que joueur (un message commencant par / est traite comme commande).",
    inputSchema: { message: z.string() },
    annotations: WRITE,
  },
  {
    name: "get_chat",
    method: "chat.recent",
    title: "Lire le chat recent",
    description: "Derniers messages recus : chat des joueurs, messages systeme, retours de commandes.",
    inputSchema: { limit: z.number().int().min(1).max(500).optional().default(50) },
    annotations: READ,
  },
  {
    name: "poll_events",
    method: "events.poll",
    title: "Lire les evenements",
    description:
      "Evenements recents du client : chat, system_message, join, disconnect, focus_changed, resources_reloaded. " +
      "Passer sinceId (le lastId de l'appel precedent) pour ne recevoir que les nouveaux.",
    inputSchema: {
      limit: z.number().int().min(1).max(1000).optional().default(100),
      types: z.array(z.string()).optional(),
      sinceId: z.number().int().min(0).optional().default(0),
    },
    annotations: READ,
  },

  // ===== client ================================================================================
  {
    name: "get_client_options",
    method: "client.getOptions",
    title: "Lire les options client",
    description: "hideGui, fov, guiScale, renderDistance, gamma, taille de la fenetre.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "set_client_options",
    method: "client.setOptions",
    title: "Modifier les options client",
    description:
      "Modifie une ou plusieurs options : hideGui (HUD), fov (30-110), guiScale (0 = auto), renderDistance (2-64), gamma (0-1). " +
      "Les options non fournies sont inchangees. Renvoie les nouvelles valeurs.",
    inputSchema: {
      hideGui: z.boolean().optional(),
      fov: z.number().int().min(30).max(110).optional(),
      guiScale: z.number().int().min(0).max(8).optional(),
      renderDistance: z.number().int().min(2).max(64).optional(),
      gamma: z.number().min(0).max(1).optional(),
    },
    annotations: WRITE,
  },
  {
    name: "reload_resource_pack",
    method: "resources.reload",
    title: "Recharger les ressources (F3+T)",
    description:
      "Recharge packs de ressources, modeles, textures et shaders, comme F3+T. A faire apres une modification du pack. " +
      "Peut prendre plusieurs secondes. Verifier ensuite get_client_log avec levels ['ERROR','WARN'] pour les erreurs de modeles.",
    inputSchema: {},
    annotations: WRITE,
  },
  {
    name: "get_client_log",
    method: "logs.client",
    title: "Lire le log client",
    description:
      "Dernieres lignes de logs/latest.log du client, avec filtre regex optionnel et filtre de niveaux (ERROR, WARN, INFO). " +
      "C'est la que remontent les erreurs de textures, modeles et shaders apres un rechargement.",
    inputSchema: {
      lines: z.number().int().min(1).max(2000).optional().default(100),
      filter: z.string().optional().describe("Regex, insensible a la casse."),
      levels: z.array(z.string()).optional().describe("Ex. ['ERROR','WARN']."),
      file: z.string().optional().default("latest.log"),
    },
    annotations: READ,
  },
  {
    name: "wait_ticks",
    method: "game.waitTicks",
    title: "Attendre des ticks",
    description: "Attend N ticks client (20 = 1 seconde, max 600). Utile apres une commande qui fait apparaitre une entite ou charge des chunks.",
    inputSchema: { ticks: z.number().int().min(1).max(600).default(20) },
    annotations: READ,
  },

  // ===== focus =================================================================================
  {
    name: "focus_entities",
    method: "focus.set",
    title: "Isoler des entites au rendu",
    description:
      "Ne rend que les entites selectionnees (par uuids, ids ou types) et masque toutes les autres, sans toucher au serveur. " +
      "Inclut par defaut les passagers et les entites display a moins de attachRadius (os ModelEngine, parties Nexo). " +
      "Masque aussi le joueur (hideSelf) et toute entite qui contiendrait la camera (hideCameraOccluders). " +
      "Fusion : les options absentes sont inchangees ; la scene (focus_scene) et la region (focus_region) sont independantes. " +
      "Reste actif jusqu'a focus_clear.",
    inputSchema: {
      uuids: z.array(z.string()).optional(),
      ids: z.array(z.number().int()).optional(),
      types: z.array(z.string()).optional().describe("Ex. ['zombie'] ou ['minecraft:item_display']."),
      attachRadius: z.number().min(0).max(32).optional().describe("Rayon d'attache des displays autour des entites selectionnees (defaut 4)."),
      includePassengers: z.boolean().optional(),
      includeAttachedDisplays: z.boolean().optional(),
      hideOthers: z.boolean().optional(),
      hideSelf: z.boolean().optional(),
      hideCameraOccluders: z.boolean().optional(),
    },
    annotations: WRITE,
  },
  {
    name: "focus_scene",
    method: "focus.set",
    title: "Masquer le decor (studio)",
    description:
      "Masque des elements du rendu pour isoler un sujet, sans toucher au monde. Le plus simple est studio:true, qui allume tout le " +
      "masquage d'un coup, avec backgroundColor pour le fond. Attention : masquer le terrain ne suffit pas, panneaux, coffres et " +
      "bannieres sont des block entities dessinees a part et demandent hideBlockEntities, d'ou l'interet du raccourci. " +
      "Fusion : les options absentes sont inchangees, et les options explicites priment sur le raccourci. Reste actif jusqu'a focus_clear.",
    inputSchema: {
      studio: z.boolean().optional().describe("Raccourci : allume d'un coup terrain, ciel, particules, block entities, brouillard, overlay et eclairage plat. Les options explicites restent prioritaires."),
      hideTerrain: z.boolean().optional().describe("Blocs, y compris le terrain lointain de Voxy."),
      hideSky: z.boolean().optional().describe("Ciel, nuages et meteo."),
      hideParticles: z.boolean().optional(),
      hideBlockEntities: z.boolean().optional().describe("Panneaux, coffres, bannieres, tetes : ils sont dessines par une passe distincte du terrain et restent visibles sans cette option."),
      hideAllEntities: z.boolean().optional().describe("Masquer toutes les entites, meme celles selectionnees par focus_entities : pour photographier un decor seul."),
      disableFog: z.boolean().optional(),
      disableCameraClipping: z.boolean().optional().describe("Empeche la camera de se rapprocher quand un bloc la gene, en troisieme personne."),
      hideInsideBlockOverlay: z.boolean().optional(),
      flatLighting: z.boolean().optional().describe("Eclairer les entites comme en plein jour, quelle que soit la lumiere reelle."),
      backgroundColor: z.string().optional().describe("'#RRGGBB' ou 'none'."),
    },
    annotations: WRITE,
  },
  {
    name: "focus_region",
    method: "focus.region",
    title: "Isoler une zone de blocs",
    description:
      "Ne rend que les blocs de la boite [from, to] (bornes incluses) : tout le reste est vu comme de l'air, y compris par Sodium, " +
      "et les entites hors de la boite sont masquees (hideEntitiesOutside). Declenche une reconstruction de toutes les sections " +
      "et attend qu'elle soit terminee (waitForRebuild, jusqu'a timeoutMs), ce qui peut prendre plusieurs secondes selon la distance de rendu. " +
      "clear:true retire la region. Cumulable avec focus_entities et focus_scene.",
    inputSchema: {
      from: z.object(vec3()).optional().describe("Premier coin (blocs)."),
      to: z.object(vec3()).optional().describe("Coin oppose (blocs)."),
      hideEntitiesOutside: z.boolean().optional().default(true),
      clear: z.boolean().optional().default(false).describe("Retirer la region."),
      waitForRebuild: z.boolean().optional().default(true),
      timeoutMs: z.number().int().min(1000).max(120000).optional().default(20000),
    },
    annotations: WRITE,
  },
  {
    name: "focus_clear",
    method: "focus.clear",
    title: "Desactiver le focus",
    description: "Retablit le rendu normal : selection d'entites, scene et region. Reconstruit les sections si une region etait active.",
    inputSchema: {
      waitForRebuild: z.boolean().optional().default(true),
    },
    annotations: WRITE,
  },
  {
    name: "focus_status",
    method: "focus.status",
    title: "Etat du focus",
    description: "Selection d'entites courante (avec le nombre d'entites selectionnees chargees), region et options de scene.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== interfaces ============================================================================
  {
    name: "get_gui",
    method: "gui.state",
    title: "Lire l'interface ouverte",
    description:
      "Decrit l'ecran ouvert : nom, titre, position et taille du panneau, echelle de l'interface, et pour chaque case " +
      "son numero, son rectangle a l'ecran et l'objet qu'elle contient (identifiant, quantite, nom affiche, nombre de lignes d'infobulle). " +
      "Sans image, donc sans cout en tokens : a appeler avant toute capture d'interface pour choisir la case. " +
      "Marche pour l'inventaire du joueur comme pour un menu ouvert par un plugin.",
    inputSchema: {
      includeEmpty: z.boolean().optional().default(false).describe("Inclure les cases vides."),
      includeTooltipLineCount: z.boolean().optional().default(true),
    },
    annotations: READ,
  },
  {
    name: "open_inventory",
    method: "gui.open",
    title: "Ouvrir l'inventaire du joueur",
    description:
      "Ouvre l'inventaire du joueur cote client, comme la touche E, et renvoie l'etat de l'interface. " +
      "Un menu de plugin ne s'ouvre pas ainsi : lancer sa commande avec send_command, puis lire get_gui.",
    inputSchema: {
      screen: z.enum(["inventory"]).optional().default("inventory"),
    },
    annotations: WRITE,
  },
  {
    name: "close_gui",
    method: "gui.close",
    title: "Fermer l'interface",
    description: "Ferme l'ecran ouvert et desactive le curseur virtuel.",
    inputSchema: {},
    annotations: WRITE,
  },
  {
    name: "get_item_lore",
    method: "gui.tooltip",
    title: "Lire l'infobulle d'un objet",
    description:
      "Renvoie le texte exact de l'infobulle d'une case : chaque ligne avec son texte brut, puis ses segments avec couleur " +
      "(#rrggbb ou nom vanilla), gras, italique, souligne, barre, obfusque. C'est le moyen le moins couteux et le plus fiable " +
      "de verifier un lore : couleurs, ordre des lignes, italique parasite. Utiliser screenshot_gui pour le rendu visuel.",
    inputSchema: {
      slot: z.number().int().min(0).describe("Numero de case, donne par get_gui."),
    },
    annotations: READ,
  },
  {
    name: "hover_slot",
    method: "gui.hover",
    title: "Survoler une case",
    description:
      "Place un curseur virtuel sur une case (ou aux coordonnees x, y de l'interface) pour afficher son infobulle a l'ecran. " +
      "La souris reelle du joueur ne bouge pas. Le survol reste actif jusqu'a clear:true ou la fermeture de l'interface. " +
      "Pour une simple capture, passer plutot hoverSlot a screenshot_gui, qui remet le curseur en etat ensuite.",
    inputSchema: {
      slot: z.number().int().min(0).optional(),
      x: z.number().optional().describe("Coordonnee X en unites d'interface."),
      y: z.number().optional().describe("Coordonnee Y en unites d'interface."),
      clear: z.boolean().optional().default(false).describe("Desactiver le curseur virtuel."),
    },
    annotations: WRITE,
  },
  {
    name: "screenshot_gui",
    method: "gui.screenshot",
    title: "Photographier une interface",
    description:
      "Capture l'interface ouverte, avec decoupage. crop : 'gui' le panneau du menu (defaut), 'slot' une seule case " +
      "(agrandie au plus proche voisin pour rester nette), 'tooltip' l'infobulle du survol, 'rect' une zone libre, 'none' tout l'ecran. " +
      "hoverSlot affiche l'infobulle de la case pendant la capture puis remet le curseur en etat. " +
      "Pour verifier un lore : hoverSlot avec crop 'tooltip'. Pour verifier une texture ou un modele : crop 'slot'. " +
      "Le HUD est masque par defaut, l'interface reste visible. Format png par defaut, mieux adapte au texte et aux textures.",
    inputSchema: {
      crop: z.enum(["gui", "slot", "tooltip", "rect", "none"]).optional().default("gui"),
      slot: z.number().int().min(0).optional().describe("Case a decouper avec crop:'slot'."),
      hoverSlot: z.number().int().min(0).optional().describe("Case a survoler pour afficher son infobulle."),
      rect: z
        .object({ x: z.number().int(), y: z.number().int(), width: z.number().int(), height: z.number().int() })
        .optional()
        .describe("Zone a decouper avec crop:'rect', en unites d'interface."),
      padding: z.number().int().min(0).max(64).optional().describe("Marge autour du decoupage (defaut 2 pour une case, 6 sinon)."),
      maxWidth: z.number().int().min(0).max(4096).optional().default(900),
      minWidth: z.number().int().min(0).max(2048).optional().describe("Largeur minimale : un decoupage etroit est agrandi (defaut 256 pour une case)."),
      format: z.enum(["png", "jpeg"]).optional().default("png"),
      quality: z.number().min(0.05).max(1).optional(),
      hideHud: z.boolean().optional().default(true),
      waitTicks: z.number().int().min(1).max(200).optional().default(3),
    },
    annotations: READ,
    kind: "image",
  },

  {
    name: "click_slot",
    method: "gui.click",
    title: "Cliquer une case",
    description:
      "Clique une case de l'interface ouverte, comme le joueur le ferait. C'est ce qui permet de parcourir un menu de plugin tout seul : " +
      "cliquer une categorie ou une page suivante, puis relire get_gui. Renvoie l'etat complet de l'interface apres le clic, avec " +
      "screenChanged et menuReplaced pour savoir si le serveur a ouvert un autre menu. " +
      "ATTENTION : le clic part reellement au serveur. Dans un menu de plugin il declenche l'action de la case, qui peut etre un achat " +
      "ou une vente autant qu'une navigation ; dans un inventaire il deplace des objets, et le champ 'carried' signale un objet reste " +
      "sur le curseur. Utiliser dryRun:true pour verifier la cible sans rien envoyer, et lire le lore de la case avant de cliquer " +
      "quand l'effet n'est pas evident. Desactive par defaut cote mod (enableGuiClicks), et limite aux types pickup et quick_move.",
    inputSchema: {
      slot: z.number().int().min(0).describe("Numero de case, donne par get_gui."),
      button: z.number().int().min(0).max(8).optional().default(0).describe("0 clic gauche, 1 clic droit ; avec type 'swap', numero de la case de la barre d'action."),
      type: z
        .enum(["pickup", "quick_move", "clone", "throw", "swap", "pickup_all", "quick_craft"])
        .optional()
        .default("pickup")
        .describe("'pickup' clic simple, 'quick_move' equivalent maj-clic. Les autres deplacent ou jettent des objets et sont refuses sauf autorisation dans la config du mod."),
      dryRun: z.boolean().optional().default(false).describe("Ne rien envoyer : renvoie seulement la case visee et son contenu."),
      waitTicks: z.number().int().min(1).max(200).optional().default(5).describe("Ticks d'attente avant de relire l'interface, le temps que le serveur reponde."),
    },
    annotations: { destructiveHint: true },
  },

  // ===== studio ================================================================================
  {
    name: "frame_target",
    method: "studio.frameTarget",
    title: "Photographier une entite (studio)",
    description:
      "Photographie une entite sous un ou plusieurs angles, tout seul : calcule le cadrage a partir de son encombrement " +
      "(entite, passagers et displays attaches, donc un mob ModelEngine ou un meuble Nexo entier), passe le joueur en spectateur, " +
      "le teleporte a la bonne distance pour chaque angle, isole la cible sur fond uni avec eclairage plein jour, capture, " +
      "puis restaure le focus, le mode de jeu, le champ de vision et la position de depart. " +
      "La distance est calculee par vue pour que la cible remplisse l'image sans etre coupee, en tenant compte du format de la fenetre. " +
      "Cible par uuid, id, ou type (l'entite chargee la plus proche). " +
      "Le cadrage se corrige tout seul : la premiere prise garantit que rien n'est coupe, puis le sujet est mesure sur le fond uni " +
      "pour rapprocher la camera (refine) et rogner l'image (autoCrop). Si le resultat ne convient pas, jouer sur margin, ou fixer distance. " +
      "studio_bounds montre la mesure retenue sans consommer d'image. " +
      "angles : front, back, left, right, top, bottom, iso, iso_left, three_quarter (azimut relatif a l'orientation de la cible). " +
      "turntable N produit N vues reparties sur 360 degres. Maximum 12 vues par appel, chacune renvoyee comme une image : " +
      "commencer par une seule vue, et utiliser studio_bounds pour verifier un cadrage sans consommer d'images. " +
      "Necessite la permission de /tp et /gamemode ; sans elle, l'appel echoue avant tout deplacement.",
    inputSchema: {
      uuid: z.string().optional(),
      id: z.number().int().optional(),
      type: z.string().optional().describe("Ex. 'item_display' ; prend l'entite chargee la plus proche."),
      angles: z.array(z.string()).optional().describe("Vues nommees. Defaut : ['front']."),
      customAngles: z
        .array(z.object({ azimuth: z.number(), pitch: z.number(), name: z.string().optional() }))
        .optional()
        .describe("Angles explicites en degres, azimut relatif a la cible."),
      turntable: z.number().int().min(0).max(12).optional().describe("Nombre de vues reparties sur 360 degres."),
      turntablePitch: z.number().min(-89).max(89).optional().default(15),
      absoluteAzimuth: z.boolean().optional().default(false).describe("Interpreter l'azimut comme un yaw monde plutot que relatif a la cible."),
      attachRadius: z.number().min(0).max(32).optional().default(4).describe("Rayon de prise en compte des displays attaches."),
      boundsSource: z
        .enum(["auto", "culling", "hitbox"])
        .optional()
        .default("auto")
        .describe(
          "Mesure du sujet. 'auto' et 'culling' utilisent la taille declaree pour le rendu, seule mesure du modele d'un display " +
            "(la hitbox d'un item_display est un point) mais souvent plus large que le modele reel. 'hitbox' s'en tient aux boites de collision.",
        ),
      margin: z
        .number()
        .min(0.5)
        .max(5)
        .optional()
        .default(1.15)
        .describe("Air autour du sujet : 1.0 colle aux bords, en dessous rogne, au-dessus eloigne. Baisser vers 0.7 si le sujet parait petit."),
      distance: z.number().min(0.5).max(256).optional().describe("Distance camera imposee et identique pour toutes les vues ; sinon calculee par vue."),
      fov: z.number().min(20).max(110).optional().describe("Champ de vision applique pendant la prise de vue puis restaure (defaut 60)."),
      studio: z.boolean().optional().default(true).describe("Isoler la cible sur fond uni ; false pour garder le decor."),
      refine: z
        .boolean()
        .optional()
        .default(true)
        .describe(
          "Regler la distance en mesurant le sujet sur le fond uni : la bonne distance est encadree par dichotomie entre une valeur ou " +
            "le sujet deborde et une ou il tient entier, et l'image renvoyee est la meilleure prise non coupee. Jusqu'a quatre captures " +
            "supplementaires par vue, pour un sujet en pleine resolution sans reglage manuel.",
        ),
      autoCrop: z.boolean().optional().default(true).describe("Rogner l'image finale sur le sujet detecte, avec une petite marge."),
      backgroundColor: z.string().optional().default("#202020").describe("Fond uni en mode studio."),
      spectator: z.boolean().optional().default(true).describe("Passer en spectateur pendant la prise de vue puis restaurer le mode precedent."),
      returnToStart: z.boolean().optional().default(true).describe("Revenir a la position de depart a la fin."),
      waitTicks: z.number().int().min(1).max(200).optional().default(6).describe("Ticks d'attente apres chaque deplacement (20 = 1 s)."),
      maxWidth: z.number().int().min(0).max(4096).optional().default(640),
      format: z.enum(["jpeg", "png"]).optional().default("jpeg"),
      quality: z.number().min(0.05).max(1).optional(),
    },
    annotations: WRITE,
    kind: "images",
  },
  {
    name: "studio_bounds",
    method: "studio.bounds",
    title: "Verifier un cadrage sans capturer",
    description:
      "Calcule l'encombrement d'une cible (boite englobante de l'entite, de ses passagers et des displays attaches, avec la liste " +
      "des elements retenus) et les positions de camera pour les angles demandes, sans rien capturer, deplacer ni modifier. " +
      "Memes parametres de cadrage que frame_target. A utiliser pour regler margin, distance et angles sans cout en images.",
    inputSchema: {
      uuid: z.string().optional(),
      id: z.number().int().optional(),
      type: z.string().optional(),
      angles: z.array(z.string()).optional(),
      customAngles: z.array(z.object({ azimuth: z.number(), pitch: z.number(), name: z.string().optional() })).optional(),
      turntable: z.number().int().min(0).max(12).optional(),
      turntablePitch: z.number().min(-89).max(89).optional(),
      absoluteAzimuth: z.boolean().optional(),
      attachRadius: z.number().min(0).max(32).optional().default(4),
      boundsSource: z.enum(["auto", "culling", "hitbox"]).optional().default("auto"),
      margin: z.number().min(0.5).max(5).optional().default(1.15),
      distance: z.number().min(0.5).max(256).optional(),
      fov: z.number().min(20).max(110).optional(),
    },
    annotations: READ,
  },

  // ===== reflexion =============================================================================
  {
    name: "reflect_invoke",
    method: "reflect.invoke",
    title: "Appeler une methode Java (client)",
    description:
      "Echappatoire : invoque n'importe quelle methode d'une classe autorisee sur le thread de rendu. Sans target, appel statique. " +
      "Les objets non serialisables reviennent comme {__type:'object_ref', __id:'obj_N'} ; assignTo les stocke sous $nom. " +
      "Exemple : className 'net.minecraft.client.Minecraft', methodName 'getInstance', assignTo 'mc'. Minecraft 26.x n'est pas obfusque, " +
      "les noms sont ceux des sources Mojang. Utiliser get_class_info pour decouvrir une classe.",
    inputSchema: {
      className: z.string(),
      methodName: z.string(),
      args: z.array(reflectArg).optional().default([]),
      target: z.string().optional().describe("'$nom' ou 'obj_N' pour un appel d'instance."),
      assignTo: z.string().optional().describe("Nom de variable ou stocker le resultat."),
    },
    annotations: WRITE,
  },
  {
    name: "reflect_get_field",
    method: "reflect.getField",
    title: "Lire un champ Java (client)",
    description: "Lit un champ (statique sans target, d'instance avec target). Remonte la hierarchie des classes.",
    inputSchema: {
      className: z.string(),
      fieldName: z.string(),
      target: z.string().optional(),
      assignTo: z.string().optional(),
    },
    annotations: READ,
  },
  {
    name: "reflect_set_field",
    method: "reflect.setField",
    title: "Modifier un champ Java (client)",
    description: "Ecrit un champ. valueType precise le type Java de la valeur (defaut : type declare du champ).",
    inputSchema: {
      className: z.string(),
      fieldName: z.string(),
      value: z.unknown(),
      valueType: z.string().optional(),
      target: z.string().optional(),
    },
    annotations: WRITE,
  },
  {
    name: "reflect_new_instance",
    method: "reflect.newInstance",
    title: "Instancier une classe Java (client)",
    description: "Construit un objet d'une classe autorisee, sur le thread de rendu.",
    inputSchema: {
      className: z.string(),
      args: z.array(reflectArg).optional().default([]),
      assignTo: z.string().optional(),
    },
    annotations: WRITE,
  },
  {
    name: "get_class_info",
    method: "reflect.classInfo",
    title: "Inspecter une classe Java",
    description: "Methodes et champs declares d'une classe (includeInherited pour les publics herites), avec filtre optionnel sur le nom.",
    inputSchema: {
      className: z.string(),
      includeInherited: z.boolean().optional().default(false),
      filter: z.string().optional().describe("Sous-chaine a chercher dans les noms."),
    },
    annotations: READ,
  },
  {
    name: "var_get",
    method: "vars.get",
    title: "Lire une variable de reflexion",
    description: "Valeur serialisee d'une variable $nom stockee par assignTo.",
    inputSchema: { name: z.string() },
    annotations: READ,
  },
  {
    name: "var_list",
    method: "vars.list",
    title: "Lister les variables de reflexion",
    description: "Noms et classes des variables stockees.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "var_delete",
    method: "vars.delete",
    title: "Supprimer une variable de reflexion",
    description: "Supprime une variable $nom.",
    inputSchema: { name: z.string() },
    annotations: WRITE,
  },
  {
    name: "var_clear",
    method: "vars.clear",
    title: "Effacer les variables et poignees",
    description: "Vide toutes les variables et references d'objets de la reflexion.",
    inputSchema: {},
    annotations: WRITE,
  },
];
