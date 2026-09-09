package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonElement;

/**
 * Un handler RPC : recoit le contexte (methode + parametres) et renvoie un JsonElement.
 *
 * <p>Regle de thread : un handler est appele depuis un thread HTTP. Tout acces a l'etat du jeu
 * (joueur, monde, options, rendu) doit passer par {@link fr.tomda.mcbridge.util.ClientMc#call}
 * ou {@link MainThread#call}, qui planifient le travail sur le thread de rendu et attendent.
 */
@FunctionalInterface
public interface RpcHandler {
	JsonElement handle(RpcContext ctx) throws Exception;
}
