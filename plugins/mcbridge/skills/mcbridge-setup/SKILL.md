---
name: mcbridge-setup
description: Installation et depannage du pont mcbridge entre Claude et un client Minecraft. A charger quand un outil mcbridge repond "bridge injoignable", quand il faut installer ou mettre a jour le mod Fabric, quand le jeton est refuse, ou quand l'utilisateur demande comment mettre en place mcbridge.
---

# mcbridge : mise en place et depannage

Le plugin fournit le serveur MCP. **Il ne sert a rien seul** : il relaie vers un mod Fabric qui doit
tourner dans un client Minecraft connecte. Sans lui, chaque appel repond « bridge injoignable ».

## Installer le mod

1. Minecraft **26.1.2**, avec **Fabric Loader 0.19.3** ou plus et **Fabric API**.
2. Copier `mod/mcbridge-0.1.0.jar` (fourni avec ce plugin) dans le dossier `mods` de l'instance.
   Pour un lanceur a profils, c'est le dossier `mods` du profil, pas `~/.minecraft/mods`.
3. Lancer Minecraft une fois. Le mod cree `config/mcbridge.json` a cote et ecrit dans le journal :
   `[mcbridge] pret : bridge http://127.0.0.1:25580`.
4. Rejoindre un monde ou un serveur. Le pont ne repond pleinement que joueur en jeu.

Verification : `get_status` doit renvoyer la version du mod et `inWorld: true`.

## Ce qui ne marche pas, et pourquoi

| Symptome | Cause la plus frequente |
|---|---|
| « bridge injoignable » | Minecraft n'est pas lance, ou le mod n'est pas dans le bon dossier `mods` |
| « jeton refuse » | Plusieurs profils : le serveur a trouve la configuration d'un autre. Renseigner l'option `config_path` du plugin |
| `inWorld: false` | Le joueur est au menu principal ou en cours de chargement |
| « Joueur introuvable » dans le chat | Un plugin serveur redefinit `/tp`. La configuration du mod utilise deja `minecraft:tp` ; verifier que le joueur a la permission |
| « Kicked for spamming » | Trop de commandes rapprochees. Augmenter `commandMinIntervalMs` dans la configuration du mod |
| Passage en spectateur refuse | Le joueur n'a pas la permission `/gamemode`. Se mettre en spectateur a la main, ou passer `spectator: false` |

## Options du plugin

Les trois options sont **a laisser vides dans le cas normal** : le serveur trouve seul la
configuration du mod dans `~/.minecraft`, dans les profils de Modrinth App (y compris l'installation
snap) et dans les instances de Prism.

- `config_path` : utile si plusieurs profils coexistent et que le mauvais est trouve.
- `bridge_url` et `token` : uniquement si le client tourne sur une autre machine. Il faut alors
  mettre `host` a `0.0.0.0` et `allowRemote` a vrai dans la configuration du mod, et ouvrir le port.

## Ce que le mod touche, et ce qu'il ne touche pas

Il observe et pilote le **client**. Il ne modifie pas le serveur de jeu, a deux exceptions
explicites : les commandes envoyees en tant que joueur, avec les permissions de ce joueur, et
`click_slot`, desactive par defaut (`enableGuiClicks`), qui envoie un vrai clic. Dans une boutique,
un clic achete pour de bon.

Les outils qui deplacent la camera passent en spectateur, puis remettent mode de jeu, position,
champ de vision et masquages en place, y compris en cas d'echec.
