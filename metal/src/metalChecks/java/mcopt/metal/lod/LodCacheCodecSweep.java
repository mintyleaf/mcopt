package mcopt.metal.lod;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Isolates word-at-a-time cache codecs from disk latency, using copied real capture files. */
public final class LodCacheCodecSweep {
	private static volatile long sink;
	private record Capture(int[] header, String[] palette, int[] words, byte[] compressed, byte[] raw) {}
	private static final class CountingInflater extends Inflater {
		long calls;
		@Override public int inflate(byte[] b, int off, int len) throws DataFormatException {
			calls++;
			return super.inflate(b, off, len);
		}
	}
	private record Read(Capture capture, long calls) {}
	private static Read read(byte[] compressed, boolean buffered) throws IOException {
		try (var inflater = new CountingInflater();
			var in = new DataInputStream(buffered
				? new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed), inflater), 16384)
				: new InflaterInputStream(new ByteArrayInputStream(compressed), inflater))) {
			int[] header = new int[4];
			for (int i = 0; i < header.length; i++) header[i] = in.readInt();
			int n = in.readInt();
			if (n < 1 || n > 1024) throw new IOException("Capture palette size: " + n);
			String[] palette = new String[n];
			for (int i = 0; i < n; i++) palette[i] = in.readUTF();
			int[] words = new int[9 * 256];
			for (int i = 0; i < words.length; i++) words[i] = in.readInt();
			return new Read(new Capture(header, palette, words, compressed, null), inflater.calls);
		}
	}
	private static byte[] write(Capture capture, boolean buffered) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var deflater = new Deflater(Deflater.BEST_SPEED);
			var out = new DataOutputStream(buffered
				? new BufferedOutputStream(new DeflaterOutputStream(bytes, deflater), 16384)
				: new DeflaterOutputStream(bytes, deflater))) {
			for (int v : capture.header) out.writeInt(v);
			out.writeInt(capture.palette.length);
			for (String name : capture.palette) out.writeUTF(name);
			for (int v : capture.words) out.writeInt(v);
		}
		return bytes.toByteArray();
	}
	private static byte[] inflate(byte[] compressed) throws IOException {
		try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) { return in.readAllBytes(); }
	}
	private record Result(long samples, long nanos, long cpuNanos, long calls) {}
	private static Result run(List<Capture> fixtures, boolean buffered, boolean writing, long duration) throws IOException {
		var bean = ManagementFactory.getThreadMXBean();
		long cpuStart = bean.getCurrentThreadCpuTime(), start = System.nanoTime(), count = 0, calls = 0, checksum = 0;
		do {
			for (Capture fixture : fixtures) {
				if (writing) checksum += write(fixture, buffered).length;
				else {
					Read result = read(fixture.compressed, buffered);
					checksum += result.capture.words[(int) (count % (9 * 256))];
					calls += result.calls;
				}
				count++;
			}
		} while (System.nanoTime() - start < duration);
		sink = checksum;
		return new Result(count, System.nanoTime() - start, bean.getCurrentThreadCpuTime() - cpuStart, calls);
	}
	public static void main(String[] args) throws Exception {
		var fixtures = new ArrayList<Capture>();
		try (var paths = Files.list(Path.of(args[0]))) {
			for (Path file : paths.filter(p -> p.toString().endsWith(".lodc")).sorted().toList()) {
				byte[] compressed = Files.readAllBytes(file), raw = inflate(compressed);
				Capture a = read(compressed, false).capture, b = read(compressed, true).capture;
				if (!Arrays.equals(a.header, b.header) || !Arrays.equals(a.palette, b.palette) || !Arrays.equals(a.words, b.words))
					throw new AssertionError("Read differs: " + file);
				for (boolean buffered : new boolean[] {false, true})
					if (!Arrays.equals(raw, inflate(write(a, buffered)))) throw new AssertionError("Write differs: " + file);
				fixtures.add(new Capture(a.header, a.palette, a.words, compressed, raw));
			}
		}
		if (fixtures.isEmpty()) throw new IllegalArgumentException("No capture fixtures");
		// Warm all paths and rotate order each round to limit compilation/clock drift bias.
		for (int round = 0; round < 4; round++) for (int v = 0; v < 4; v++) {
			int variant = (v + round) % 4;
			run(fixtures, (variant & 1) != 0, variant >= 2, 250_000_000L);
		}
		Path output = Path.of(args[1]);
		Files.createDirectories(output.toAbsolutePath().getParent());
		try (var csv = Files.newBufferedWriter(output)) {
			csv.write("round,operation,buffered,samples,mean_wall_us,mean_thread_cpu_us,inflate_calls_per_capture\n");
			for (int round = 0; round < 12; round++) for (int v = 0; v < 4; v++) {
				int variant = (v + round) % 4;
				boolean buffered = (variant & 1) != 0, writing = variant >= 2;
				Result result = run(fixtures, buffered, writing, 250_000_000L);
				csv.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%.4f,%.4f,%.2f%n", round, writing ? "write" : "read", buffered,
					result.samples, result.nanos / (1000.0 * result.samples), result.cpuNanos / (1000.0 * result.samples), result.calls / (double) result.samples));
			}
		}
		System.out.println("PASS: old/new reads and both writer payloads match " + fixtures.size() + " copied real captures; " + output);
	}
}
