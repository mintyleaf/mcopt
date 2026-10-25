package mcopt.metal;

import java.util.HashMap;
import java.util.HashSet;
import net.minecraft.resources.Identifier;

public final class CaveTexturePreloadChecks {
	public static void main(String[] args) {
		var textures = new HashMap<Identifier, Object>();
		var first = CaveTexturePreload.TEXTURES.getFirst();
		Object existing = new Object();textures.put(first, existing);
		var added = new HashSet<Identifier>();
		CaveTexturePreload.register(textures, id -> {added.add(id);textures.put(id,new Object());});
		if(CaveTexturePreload.TEXTURES.size()!=5 || added.size()!=4 || textures.get(first)!=existing)
			throw new AssertionError("Texture warmup replaced an existing registration or changed its fixed bound");
		CaveTexturePreload.register(textures, id -> {throw new AssertionError("Repeated registration");});
		for(String flag : new String[]{"mcopt.preload","mcopt.preload.firstRender","mcopt.preload.gameplay","mcopt.preload.caveTextures"}) {
			System.setProperty(flag,"false");
			CaveTexturePreload.register(java.util.Map.of(), id -> {throw new AssertionError("Disabled group registered a texture");});
			System.clearProperty(flag);
		}
		if(!textures.keySet().equals(new HashSet<>(CaveTexturePreload.TEXTURES)))throw new AssertionError("Unexpected resources");
		System.out.println("PASS: five bounded cave textures; existing registrations preserved, idempotent and parent/feature opt-outs honored");
	}
}
