package zakyatto.meowlogger.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** The format of the NBT columns ({@code old_data}, {@code new_data}): gzip-compressed binary NBT. */
public final class NbtBlobs {
	private NbtBlobs() {
	}

	/** Compresses {@code tag}, or returns null for null. */
	public static byte[] compress(CompoundTag tag) {
		if (tag == null) {
			return null;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
		try {
			NbtIo.writeCompressed(tag, bytes);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // cannot happen for an in-memory stream
		}
		return bytes.toByteArray();
	}

	/**
	 * Decompresses {@code bytes}, or returns null for null.
	 *
	 * @throws IOException if the bytes are not valid compressed NBT
	 */
	public static CompoundTag decompress(byte[] bytes) throws IOException {
		if (bytes == null) {
			return null;
		}
		try {
			return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
		} catch (RuntimeException e) {
			throw new IOException("Corrupt NBT data", e);
		}
	}
}
