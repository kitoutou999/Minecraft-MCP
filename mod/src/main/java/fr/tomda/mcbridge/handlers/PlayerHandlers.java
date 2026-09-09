package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.util.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** {@code player.getState} : position, orientation, dimension, mode de jeu et vitaux du joueur local. */
public final class PlayerHandlers {
	private PlayerHandlers() {}

	public static void register(RpcRouter router) {
		router.register("player.getState", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			Minecraft mc = ClientMc.mc();
			JsonObject o = new JsonObject();
			o.addProperty("name", p.getName().getString());
			o.addProperty("uuid", p.getStringUUID());
			o.add("pos", Json.vec(p.position()));
			o.add("eyePos", Json.vec(p.getEyePosition()));
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			o.addProperty("dimension", p.level().dimension().identifier().toString());
			o.addProperty("gameMode", mc.gameMode != null ? mc.gameMode.getPlayerMode().getSerializedName() : "unknown");
			o.addProperty("health", p.getHealth());
			o.addProperty("maxHealth", p.getMaxHealth());
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("onGround", p.onGround());
			o.addProperty("flying", p.getAbilities().flying);
			o.addProperty("mayFly", p.getAbilities().mayfly);
			o.addProperty("spectator", p.isSpectator());
			o.addProperty("creative", p.isCreative());
			o.addProperty("sneaking", p.isShiftKeyDown());
			o.addProperty("sprinting", p.isSprinting());
			o.addProperty("inWater", p.isInWater());
			return o;
		}));
	}
}
