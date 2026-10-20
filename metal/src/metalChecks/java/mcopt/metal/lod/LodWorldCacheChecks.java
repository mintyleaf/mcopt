package mcopt.metal.lod;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LodWorldCacheChecks {
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var registries = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		var preset = registries.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
			.getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
		var plains = registries.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
		var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(plains),preset);
		var encoded = LodWorldCache.generation(generator,preset.value(),registries);
		if (!encoded.equals(LodWorldCache.generation(generator,preset.value(),registries)))
			throw new AssertionError("Real registry-backed generation settings cannot produce a stable persistent identity");
		Path temporary = Files.createTempDirectory("mcopt-world-cache-");
		try {
			Path save = temporary.resolve("saves/New World (1)"), root = temporary.resolve("cache");
			var settings = JsonParser.parseString("{\"generator\":{\"type\":\"minecraft:noise\",\"settings\":\"minecraft:overworld\"},\"sea_level\":63}");
			var reordered = JsonParser.parseString("{\"sea_level\":63,\"generator\":{\"settings\":\"minecraft:overworld\",\"type\":\"minecraft:noise\"}}");
			Path worldA = LodWorldCache.directory(root, save, "created-A", 8042271463522685457L, "minecraft:overworld", settings);
			Path worldB = LodWorldCache.directory(root, save, "created-B", -7640742383989886661L, "minecraft:overworld", settings);
			Path sameSeed = LodWorldCache.directory(root, save, "created-A", -7640742383989886661L, "minecraft:overworld", settings);
			Path recreated = LodWorldCache.directory(root, save, "created-B", 8042271463522685457L, "minecraft:overworld", settings);
			if (worldA.equals(worldB) || worldA.equals(sameSeed) || worldA.equals(recreated))
				throw new AssertionError("Reused folder, changed seed or same-seed recreation inherits another world's terrain/builds");
			if (!worldA.equals(LodWorldCache.directory(root, save.resolve("."), "created-A", 8042271463522685457L, "minecraft:overworld", reordered)))
				throw new AssertionError("Normal relaunch/map ordering loses the existing identified cache");
			for (Path other : new Path[]{
				LodWorldCache.directory(root, save, "created-A", 8042271463522685457L, "minecraft:the_end", settings),
				LodWorldCache.directory(root, temporary.resolve("other-instance/saves/New World (1)"), "created-A", 8042271463522685457L, "minecraft:overworld", settings),
				LodWorldCache.directory(root, save, "created-A", 8042271463522685457L, "minecraft:overworld", JsonParser.parseString("{\"sea_level\":100}"))})
				if (other.equals(worldA)) throw new AssertionError("Different dimension, save or generation settings shares terrain/builds");
			Path legacy = root.resolve("New World (1)/minecraft_overworld/L0/0.0.lod");
			Files.createDirectories(legacy.getParent());Files.writeString(legacy,"unidentified old seed");
			if (Files.exists(worldA.resolve("L0/0.0.lod")) || Files.exists(worldB.resolve("L0/0.0.lod")))
				throw new AssertionError("Legacy folder-name-only cache was adopted without identity");
			Files.createDirectories(worldA.resolve("L0"));Files.createDirectories(worldB.resolve("L0"));
			Files.writeString(worldB.resolve("L0/0.0.lod"),"new world");
			Files.writeString(worldA.resolve("L0/0.0.lod"),"late old-world worker");
			if (!Files.readString(worldB.resolve("L0/0.0.lod")).equals("new world") || !Files.readString(legacy).equals("unidentified old seed"))
				throw new AssertionError("Late writes contaminate the new seed or destroy the old cache");
			System.out.println("PASS: reused folders, seed changes, same-seed recreation, dimensions, shared roots and settings stay isolated; relaunch reuses its cache; legacy and late-worker writes cannot contaminate a new world");
		} finally {
			try (var files = Files.walk(temporary)) {
				for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
			}
		}
	}
}
