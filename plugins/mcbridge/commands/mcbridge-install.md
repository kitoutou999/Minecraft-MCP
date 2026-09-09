---
description: Installe le mod Minecraft dont mcbridge a besoin, puis verifie que le pont repond.
---

Installe le mod Fabric livre avec ce plugin dans une instance Minecraft de l'utilisateur, puis
verifie que tout repond. Le serveur MCP seul ne sert a rien : il relaie vers ce mod.

Marche a suivre.

1. **Trouver le jar livre avec le plugin.**

   ```bash
   ls ~/.claude/plugins/cache/*/mcbridge/*/mod/*.jar
   ```

2. **Trouver les dossiers de mods de l'utilisateur.** Prendre le premier qui existe, et lister les
   autres s'il y en a plusieurs.

   ```bash
   ls -d ~/.minecraft/mods \
         ~/.local/share/ModrinthApp/profiles/*/mods \
         ~/snap/modrinth/common/.local/share/ModrinthApp/profiles/*/mods \
         ~/.local/share/PrismLauncher/instances/*/minecraft/mods \
         ~/Library/Application\ Support/ModrinthApp/profiles/*/mods \
         "$APPDATA"/.minecraft/mods 2>/dev/null
   ```

   S'il y en a plusieurs, **demander lequel** plutot que de choisir : un joueur a souvent une
   instance de jeu et une instance de developpement.

3. **Verifier la version.** Le mod vise **Minecraft 26.1.2** avec Fabric Loader 0.19.3 ou plus et
   Fabric API. Chercher `fabric-api-*.jar` dans le dossier choisi : s'il manque, le dire, le mod ne
   se chargera pas sans lui.

4. **Copier le jar** dans le dossier retenu.

5. **Demander a l'utilisateur de lancer Minecraft et de rejoindre un monde ou un serveur.** Le mod
   cree alors sa configuration et ouvre le pont. Ne pas essayer de lancer le jeu soi-meme.

6. **Verifier** avec l'outil `get_status`. Il doit renvoyer la version du mod et `inWorld: true`.
   En cas d'erreur, la competence `mcbridge-setup` liste les pannes courantes et leur cause.

Ne rien installer ailleurs, ne pas modifier les autres mods, et ne pas toucher aux mondes.
