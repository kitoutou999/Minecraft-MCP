# mcbridge, plugin Claude Code

Donne a Claude les yeux d'un joueur Minecraft : captures, isolation d'un sujet sur fond uni, cadrage
automatique d'une entite, lecture des inventaires et des lores, planches d'animation, detection des
regressions visuelles apres un changement de pack de ressources.

## Installation

```
/plugin marketplace add kitoutou999/Minecraft-MCP
/plugin install mcbridge@minecraft-mcp
```

Puis le mod, sans lequel rien ne repond : copier `mod/mcbridge-0.1.0.jar` dans le dossier `mods`
d'une instance **Minecraft 26.1.2** avec Fabric Loader et Fabric API, lancer le jeu une fois, et
rejoindre un monde. Le detail et le depannage sont dans la competence `mcbridge-setup`, que Claude
charge tout seul en cas de probleme.

## Contenu

```
.claude-plugin/plugin.json   manifeste
.mcp.json                    declaration du serveur MCP
server/mcbridge-mcp.mjs      serveur MCP, fichier unique sans dependances
mod/                         le mod Fabric a copier dans l'instance
skills/mcbridge-setup/       installation et depannage, charge a la demande
```

Les deux artefacts sont produits depuis les sources du depot par `scripts/build-plugin.sh`.

## Licence

MIT. Depot : https://github.com/kitoutou999/Minecraft-MCP
