package mcopt.metal;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;

/** Five measured first-use resources, prepared/uploaded/closed by the normal TextureManager lifecycle. */
public final class CaveTexturePreload {
	private CaveTexturePreload() { }
	static final List<Identifier> TEXTURES = List.of(
		"textures/misc/shadow.png", "textures/entity/bat/bat.png", "textures/entity/spider/spider.png",
		"textures/entity/zombie/zombie.png", "textures/entity/slime/slime.png"
	).stream().map(Identifier::withDefaultNamespace).toList();
	public static void register(Map<Identifier, ?> registered, Consumer<Identifier> nextReload) {
		if (!enabled()) return;
		for (Identifier id : TEXTURES) if (!registered.containsKey(id)) nextReload.accept(id);
	}
	private static boolean enabled() {
		for (String group : List.of("mcopt.preload", "mcopt.preload.firstRender", "mcopt.preload.gameplay", "mcopt.preload.caveTextures"))
			if (!Boolean.parseBoolean(System.getProperty(group, "true"))) return false;
		return true;
	}
}
