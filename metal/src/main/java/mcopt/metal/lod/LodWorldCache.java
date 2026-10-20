package mcopt.metal.lod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** A save folder name is reusable. Only explicitly identified terrain may enter a world's persistent cache. */
final class LodWorldCache {
	private LodWorldCache() { }

	static Path directory(Path root, Path save, LodNoise noise) {
		String dimension = noise.level.dimension().identifier().toString();
		Path directory;
		try {
			Path actual = save.toRealPath();
			String creation = Files.readAttributes(actual, BasicFileAttributes.class).creationTime().toString();
			JsonElement generation = generation(noise.level.getChunkSource().getGenerator(), noise.settings, noise.level.registryAccess());
			directory = directory(root, actual, creation, noise.level.getSeed(), dimension, generation);
		} catch (java.io.IOException | RuntimeException e) {
			// Failure to identify a world must never fall back to a folder-name-only cache. A fresh session can still render.
			directory = root.resolve(save.toAbsolutePath().normalize().getFileName()).resolve("session-" + java.util.UUID.randomUUID());
			System.out.println("mcopt-lod: persistent world identity unavailable, using a fresh session cache: " + e);
		}
		System.out.println("mcopt-lod: world-bound cache " + directory);
		return directory;
	}

	static JsonElement generation(ChunkGenerator generator, NoiseGeneratorSettings settings, net.minecraft.core.HolderLookup.Provider registries) {
		var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		JsonObject generation = new JsonObject();
		generation.addProperty("minecraft", SharedConstants.getCurrentVersion().id());
		generation.add("generator", ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow());
		generation.add("noise_settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow());
		generation.addProperty("fine_density", LodConfig.FINE_DENSITY);
		generation.addProperty("fine_levels", LodConfig.FINE_LEVELS);
		generation.addProperty("trees", LodConfig.TREES);
		return generation;
	}

	static Path directory(Path root, Path save, String creation, long seed, String dimension, JsonElement generation) {
		String identity = fingerprint(save, creation, seed, dimension, generation);
		// Old L0/L1 directories have no seed/provenance and are deliberately not adopted or deleted.
		return root.resolve(save.toAbsolutePath().normalize().getFileName()).resolve(dimension.replace(':', '_'))
			.resolve("world-" + identity);
	}

	static String fingerprint(Path save, String creation, long seed, String dimension, JsonElement generation) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// v1 accepted chunk callbacks before their dimension's first frame; those captures may be cross-dimension.
			for (String part : new String[]{"mcopt-world-cache-2", save.toAbsolutePath().normalize().toString(), creation,
				Long.toString(seed), dimension, canonical(generation).toString()}) {
				byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
				digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
				digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest(), 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Codec maps may have a different iteration order on a later launch; their contents define the identity. */
	private static JsonElement canonical(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject sorted = new JsonObject();
			value.getAsJsonObject().keySet().stream().sorted().forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key))));
			return sorted;
		}
		if (value.isJsonArray()) {
			JsonArray ordered = new JsonArray();
			value.getAsJsonArray().forEach(v -> ordered.add(canonical(v)));
			return ordered;
		}
		return value;
	}
}
