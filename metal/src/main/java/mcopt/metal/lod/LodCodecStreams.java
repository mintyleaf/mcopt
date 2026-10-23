package mcopt.metal.lod;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Reuse only temporary codec bytes. Decoded planes always keep independent ownership. */
final class LodCodecStreams {
	private static final int SIZE = 65536;
	private static final ThreadLocal<Buffers> LOCAL = ThreadLocal.withInitial(Buffers::new);
	private static final class Buffers {
		final byte[] compressed = new byte[SIZE], words = new byte[SIZE];
		boolean borrowed;
	}
	private static Buffers borrow() {
		Buffers b = LOCAL.get();
		if (b.borrowed) b = new Buffers(); // Nested legacy repairs must not overwrite an outer stream's unread words.
		b.borrowed = true;
		return b;
	}
	private static final class Inflate extends InflaterInputStream {
		Inflate(InputStream in, byte[] bytes) { super(in); this.buf = bytes; }
	}
	private static final class ReadWords extends BufferedInputStream {
		ReadWords(InputStream in, byte[] bytes) { super(in, 1); this.buf = bytes; }
	}
	private static final class Deflate extends DeflaterOutputStream {
		Deflate(OutputStream out, Deflater compressor, byte[] bytes) { super(out, compressor, 1); this.buf = bytes; }
	}
	private static final class WriteWords extends BufferedOutputStream {
		WriteWords(OutputStream out, byte[] bytes) { super(out, 1); this.buf = bytes; }
	}
	static DataInputStream input(Path file) throws IOException {
		Buffers b = borrow();
		try {
			return new DataInputStream(new ReadWords(new Inflate(Files.newInputStream(file), b.compressed), b.words)) {
				private boolean returned;
				@Override public void close() throws IOException { if (this.returned) return; this.returned = true; try { super.close(); } finally { b.borrowed = false; } }
			};
		} catch (IOException | RuntimeException | Error failure) { b.borrowed = false; throw failure; }
	}
	/** Caller owns compressor and ends it after the stream closes. */
	static DataOutputStream output(Path file, Deflater compressor) throws IOException {
		Buffers b = borrow();
		try {
			return new DataOutputStream(new WriteWords(new Deflate(Files.newOutputStream(file), compressor, b.compressed), b.words)) {
				private boolean returned;
				@Override public void close() throws IOException { if (this.returned) return; this.returned = true; try { super.close(); } finally { b.borrowed = false; } }
			};
		} catch (IOException | RuntimeException | Error failure) { b.borrowed = false; throw failure; }
	}
}
