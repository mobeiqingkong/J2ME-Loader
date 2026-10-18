package ru.playsoftware.j2meloader.trainer;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Searches and patches the memory of the emulator process itself.
 * <p>
 * The MIDlet runs in the same process as this code, so the game state can be reached
 * through {@code /proc/self/mem} without root: the kernel allows a process to read and
 * write its own memory. Only anonymous, writable regions are scanned - code, libraries
 * and shared file mappings are skipped.
 */
public final class MemoryTrainer {

	private static final String TAG = "MemoryTrainer";
	/**
	 * Size of a single read from /proc/self/mem. Reads of huge ranges may fail when a
	 * gap of unmapped pages is crossed, so the scan is split into aligned blocks.
	 */
	private static final int CHUNK_SIZE = 1 << 20;
	/**
	 * Upper bound of the memory to scan. A first search touches the whole heap and
	 * takes a couple of seconds, the limit keeps the worst case predictable.
	 */
	private static final long MAX_SCAN_BYTES = 768L << 20;
	/**
	 * A very common value ("0", "100") may match millions of ints. The list is capped
	 * to keep the UI responsive, the user is expected to refine the search.
	 */
	public static final int MAX_RESULTS = 200_000;

	public static final int TYPE_DWORD = 0;
	public static final int TYPE_FLOAT = 1;
	public static final int TYPE_WORD = 2;
	public static final int TYPE_BYTE = 3;
	public static final int TYPE_QWORD = 4;
	/**
	 * Pseudo type for the spinner: the value is searched as every supported type at once.
	 */
	public static final int TYPE_ALL = 5;

	private static final String[] TYPE_NAMES = {"DWORD", "FLOAT", "WORD", "BYTE", "QWORD", "ALL"};

	public static final String[] TYPE_LABELS = {
			"整数 4字节 (DWORD)", "小数 4字节 (FLOAT)", "整数 2字节 (WORD)",
			"整数 1字节 (BYTE)", "整数 8字节 (QWORD)", "全部类型（较慢）"
	};

	/**
	 * One match: the address and the type the value was found as.
	 */
	public static final class Hit {
		public final long address;
		public final int type;

		public Hit(long address, int type) {
			this.address = address;
			this.type = type;
		}
	}

	private MemoryTrainer() {
	}

	public static String typeName(int type) {
		return TYPE_NAMES[type];
	}

	/**
	 * @return one letter tag used in the result list, the panel is too narrow
	 * for the full name next to the address and the value
	 */
	public static String typeTag(int type) {
		switch (type) {
			case TYPE_FLOAT:
				return "F";
			case TYPE_WORD:
				return "W";
			case TYPE_BYTE:
				return "B";
			case TYPE_QWORD:
				return "Q";
			default:
				return "D";
		}
	}

	/**
	 * @return size of the value in bytes, also the scan step, because ART keeps
	 * fields of a structure aligned to their size
	 */
	public static int valueSize(int type) {
		switch (type) {
			case TYPE_WORD:
				return 2;
			case TYPE_BYTE:
				return 1;
			case TYPE_QWORD:
				return 8;
			default:
				return 4;
		}
	}

	/**
	 * Converts the text entered by the user into the little-endian bytes to look for.
	 *
	 * @throws NumberFormatException if the text can not be parsed as the given type
	 */
	public static byte[] pattern(int type, String text) throws NumberFormatException {
		String s = text.trim();
		if (s.isEmpty()) {
			throw new NumberFormatException("empty");
		}
		switch (type) {
			case TYPE_FLOAT: {
				float v = Float.parseFloat(s);
				return le32(Float.floatToRawIntBits(v));
			}
			case TYPE_QWORD: {
				long v = parseLong(s);
				byte[] b = new byte[8];
				for (int i = 0; i < 8; i++) {
					b[i] = (byte) (v >>> (i * 8));
				}
				return b;
			}
			case TYPE_WORD: {
				int v = (int) parseLong(s);
				if (v < Short.MIN_VALUE || v > (Short.MAX_VALUE & 0xFFFF)) {
					throw new NumberFormatException("out of WORD range");
				}
				return new byte[]{(byte) v, (byte) (v >>> 8)};
			}
			case TYPE_BYTE: {
				int v = (int) parseLong(s);
				if (v < Byte.MIN_VALUE || v > (Byte.MAX_VALUE & 0xFF)) {
					throw new NumberFormatException("out of BYTE range");
				}
				return new byte[]{(byte) v};
			}
			default: {
				int v = (int) parseLong(s);
				return le32(v);
			}
		}
	}

	private static long parseLong(String s) {
		String v = s.trim();
		boolean negative = v.startsWith("-");
		String abs = negative ? v.substring(1) : v;
		if (abs.startsWith("0x") || abs.startsWith("0X")) {
			long parsed = Long.parseLong(abs.substring(2), 16);
			return negative ? -parsed : parsed;
		}
		return Long.parseLong(v);
	}

	private static byte[] le32(int v) {
		return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
	}

	private static int readLe32(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
				| ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static long readLe64(byte[] b, int off) {
		return (readLe32(b, off) & 0xFFFFFFFFL) | ((long) readLe32(b, off + 4) << 32);
	}

	/**
	 * @return the value stored at the address formatted for the result list,
	 * or {@code null} if the address is not readable anymore
	 */
	public static String readValue(long address, int type) {
		byte[] data = readBytes(address, valueSize(type));
		if (data == null) return null;
		switch (type) {
			case TYPE_FLOAT:
				return String.valueOf(Float.intBitsToFloat(readLe32(data, 0)));
			case TYPE_WORD:
				return String.valueOf((short) ((data[0] & 0xFF) | (data[1] << 8)));
			case TYPE_BYTE:
				return String.valueOf(data[0]);
			case TYPE_QWORD:
				return String.valueOf(readLe64(data, 0));
			default:
				return String.valueOf(readLe32(data, 0));
		}
	}

	public static boolean writeValue(long address, int type, byte[] pattern) {
		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "rw")) {
			mem.seek(address);
			mem.write(pattern);
			return true;
		} catch (IOException e) {
			Log.w(TAG, "write " + Long.toHexString(address) + " failed: " + e);
			return false;
		}
	}

	private static byte[] readBytes(long address, int size) {
		byte[] b = new byte[size];
		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
			mem.seek(address);
			if (mem.read(b) != size) {
				return null;
			}
			return b;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Runs the first search.
	 *
	 * @param type    one of the TYPE_ constants
	 * @param pattern the bytes to look for
	 * @param listener progress callback, may be {@code null}
	 * @return found addresses, limited to {@link #MAX_RESULTS}
	 */
	public static List<Long> search(int type, byte[] pattern, Listener listener) {
		final List<Long> results = new ArrayList<>();
		final List<long[]> regions = collectRegions();
		long total = 0;
		for (long[] r : regions) {
			total += r[1] - r[0];
		}
		if (total > MAX_SCAN_BYTES) {
			total = MAX_SCAN_BYTES;
		}
		final int step = valueSize(type);
		final byte[] buffer = new byte[CHUNK_SIZE];
		long scanned = 0;

		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
			for (long[] region : regions) {
				long pos = region[0];
				final long end = region[1];
				while (pos < end) {
					if (listener != null && listener.isCancelled()) {
						return results;
					}
					int want = (int) Math.min(CHUNK_SIZE, end - pos);
					want -= want % step;
					if (want < step) {
						break;
					}
					int read = readFully(mem, pos, buffer, want);
					if (read < step) {
						pos += 4096;
						continue;
					}
					read -= read % step;
					scan(buffer, read, pos, pattern, step, results);
					pos += read;
					scanned += read;
					if (listener != null) {
						listener.onProgress(scanned, total);
					}
					if (results.size() >= MAX_RESULTS || scanned >= MAX_SCAN_BYTES) {
						return results;
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "search failed", e);
		}
		return results;
	}

	/**
	 * Keeps the addresses which still hold the given value.
	 */
	public static List<Long> refine(int type, byte[] pattern, List<Long> addresses) {
		final int size = pattern.length;
		final byte[] probe = new byte[size];
		final List<Long> kept = new ArrayList<>();
		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
			for (Long address : addresses) {
				try {
					mem.seek(address);
					if (mem.read(probe) != size) {
						continue;
					}
				} catch (IOException e) {
					continue;
				}
				boolean match = true;
				for (int i = 0; i < size; i++) {
					if (probe[i] != pattern[i]) {
						match = false;
						break;
					}
				}
				if (match) {
					kept.add(address);
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "refine failed", e);
		}
		return kept;
	}

	private static int readFully(RandomAccessFile mem, long pos, byte[] buf, int len) {
		try {
			mem.seek(pos);
			int off = 0;
			while (off < len) {
				int n = mem.read(buf, off, len - off);
				if (n <= 0) {
					break;
				}
				off += n;
			}
			return off;
		} catch (IOException e) {
			// a gap of unmapped pages: the caller skips a page and retries
			return -1;
		}
	}

	private static void scan(byte[] b, int len, long base, byte[] pattern, int step,
							 List<Long> out) {
		final int n = pattern.length;
		final int limit = len - n;
		switch (n) {
			case 1: {
				final byte p0 = pattern[0];
				for (int i = 0; i <= limit; i += step) {
					if (b[i] == p0) {
						add(out, base + i);
					}
				}
				break;
			}
			case 2: {
				final byte p0 = pattern[0], p1 = pattern[1];
				for (int i = 0; i <= limit; i += step) {
					if (b[i] == p0 && b[i + 1] == p1) {
						add(out, base + i);
					}
				}
				break;
			}
			case 4: {
				final byte p0 = pattern[0], p1 = pattern[1], p2 = pattern[2], p3 = pattern[3];
				for (int i = 0; i <= limit; i += step) {
					if (b[i] == p0 && b[i + 1] == p1 && b[i + 2] == p2 && b[i + 3] == p3) {
						add(out, base + i);
					}
				}
				break;
			}
			default: {
				for (int i = 0; i <= limit; i += step) {
					boolean match = true;
					for (int j = 0; j < n; j++) {
						if (b[i + j] != pattern[j]) {
							match = false;
							break;
						}
					}
					if (match) {
						add(out, base + i);
					}
				}
			}
		}
	}

	/**
	 * @return the patterns for every type that can parse the given text, keyed by type
	 */
	public static java.util.Map<Integer, byte[]> patternAll(String text) {
		java.util.Map<Integer, byte[]> map = new java.util.LinkedHashMap<>();
		int[] order = {TYPE_QWORD, TYPE_DWORD, TYPE_FLOAT, TYPE_WORD, TYPE_BYTE};
		for (int type : order) {
			try {
				map.put(type, pattern(type, text));
			} catch (NumberFormatException ignored) {
				// the text is not representable as this type, skip it
			}
		}
		return map;
	}

	public static String readHit(Hit hit) {
		return readValue(hit.address, hit.type);
	}

	/**
	 * Reads the current value of every hit with a single file handle, the result list
	 * is refreshed periodically while the panel is open.
	 */
	public static List<String> readHits(List<Hit> hits) {
		List<String> values = new ArrayList<>(hits.size());
		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
			final byte[] probe = new byte[8];
			for (Hit hit : hits) {
				int size = valueSize(hit.type);
				try {
					mem.seek(hit.address);
					if (mem.read(probe, 0, size) != size) {
						values.add("?");
						continue;
					}
				} catch (IOException e) {
					values.add("?");
					continue;
				}
				values.add(format(probe, hit.type));
			}
		} catch (IOException e) {
			Log.e(TAG, "readHits failed", e);
		}
		return values;
	}

	private static String format(byte[] data, int type) {
		switch (type) {
			case TYPE_FLOAT:
				return String.valueOf(Float.intBitsToFloat(readLe32(data, 0)));
			case TYPE_WORD:
				return String.valueOf((short) ((data[0] & 0xFF) | (data[1] << 8)));
			case TYPE_BYTE:
				return String.valueOf(data[0]);
			case TYPE_QWORD:
				return String.valueOf(readLe64(data, 0));
			default:
				return String.valueOf(readLe32(data, 0));
		}
	}

	public static boolean writeHit(Hit hit, String text) {
		try {
			return writeValue(hit.address, hit.type, pattern(hit.type, text));
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Searches the value as every type at once in a single pass. Every address is
	 * reported once, with the largest type that matches, so a single byte hit does
	 * not hide the integer stored at the same address.
	 */
	public static List<Hit> searchAll(java.util.Map<Integer, byte[]> patterns, Listener listener) {
		final List<Hit> results = new ArrayList<>();
		final List<long[]> regions = collectRegions();
		long total = 0;
		for (long[] r : regions) {
			total += r[1] - r[0];
		}
		if (total > MAX_SCAN_BYTES) {
			total = MAX_SCAN_BYTES;
		}
		final byte[] pQword = patterns.get(TYPE_QWORD);
		final byte[] pDword = patterns.get(TYPE_DWORD);
		final byte[] pFloat = patterns.get(TYPE_FLOAT);
		final byte[] pWord = patterns.get(TYPE_WORD);
		final byte[] pByte = patterns.get(TYPE_BYTE);
		final byte[] buffer = new byte[CHUNK_SIZE];
		long scanned = 0;

		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
			for (long[] region : regions) {
				long pos = region[0];
				final long end = region[1];
				while (pos < end) {
					if (listener != null && listener.isCancelled()) {
						return results;
					}
					int want = (int) Math.min(CHUNK_SIZE, end - pos);
					want -= want % 8;
					if (want < 8) {
						break;
					}
					int read = readFully(mem, pos, buffer, want);
					if (read < 8) {
						pos += 4096;
						continue;
					}
					read -= read % 8;
					scanMixed(buffer, read, pos, pQword, pDword, pFloat, pWord, pByte, results);
					pos += read;
					scanned += read;
					if (listener != null) {
						listener.onProgress(scanned, total);
					}
					if (results.size() >= MAX_RESULTS || scanned >= MAX_SCAN_BYTES) {
						return results;
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "searchAll failed", e);
		}
		return results;
	}

	private static void scanMixed(byte[] b, int len, long base, byte[] pQword, byte[] pDword,
								  byte[] pFloat, byte[] pWord, byte[] pByte, List<Hit> out) {
		final int limit = len - 8;
		for (int i = 0; i <= limit; i++) {
			// one hit per address, the largest type wins
			if ((i & 7) == 0 && pQword != null && match(b, i, pQword)) {
				out.add(new Hit(base + i, TYPE_QWORD));
			} else if ((i & 3) == 0 && pDword != null && match(b, i, pDword)) {
				out.add(new Hit(base + i, TYPE_DWORD));
			} else if ((i & 3) == 0 && pFloat != null && match(b, i, pFloat)) {
				out.add(new Hit(base + i, TYPE_FLOAT));
			} else if ((i & 1) == 0 && pWord != null && match(b, i, pWord)) {
				out.add(new Hit(base + i, TYPE_WORD));
			} else if (pByte != null && b[i] == pByte[0]) {
				out.add(new Hit(base + i, TYPE_BYTE));
			}
			if (out.size() >= MAX_RESULTS) {
				return;
			}
		}
	}

	private static boolean match(byte[] b, int offset, byte[] pattern) {
		for (int i = 0; i < pattern.length; i++) {
			if (b[offset + i] != pattern[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Keeps the hits which still hold the given value, checking every hit with its own type.
	 */
	public static List<Hit> refineAll(java.util.Map<Integer, byte[]> patterns, List<Hit> hits) {
		final List<Hit> kept = new ArrayList<>();
		try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
			final byte[] probe = new byte[8];
			for (Hit hit : hits) {
				byte[] pattern = patterns.get(hit.type);
				if (pattern == null) {
					continue;
				}
				try {
					mem.seek(hit.address);
					if (mem.read(probe, 0, pattern.length) != pattern.length) {
						continue;
					}
				} catch (IOException e) {
					continue;
				}
				if (match(probe, 0, pattern)) {
					kept.add(hit);
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "refineAll failed", e);
		}
		return kept;
	}

	private static void add(List<Long> out, long address) {
		if (out.size() < MAX_RESULTS) {
			out.add(address);
		}
	}

	/**
	 * Reads /proc/self/maps and collects the anonymous writable regions, the Java heaps
	 * first because the game state lives there.
	 */
	private static List<long[]> collectRegions() {
		final List<long[]> dalvik = new ArrayList<>();
		final List<long[]> other = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
			String line;
			while ((line = reader.readLine()) != null) {
				// 12c00000-12e00000 rw-p 00000000 00:00 0 [anon:dalvik-main space]
				int space = line.indexOf(' ');
				if (space <= 0 || space + 4 >= line.length()) continue;
				String range = line.substring(0, space);
				String perms = line.substring(space + 1, Math.min(space + 5, line.length()));
				if (!perms.startsWith("rw")) continue;

				int dash = range.indexOf('-');
				if (dash <= 0) continue;
				long start;
				long end;
				try {
					start = Long.parseLong(range.substring(0, dash), 16);
					end = Long.parseLong(range.substring(dash + 1), 16);
				} catch (NumberFormatException e) {
					continue;
				}
				if (end <= start) continue;

				int pathStart = line.indexOf('/', space);
				int nameStart = line.indexOf('[', space);
				String name = null;
				if (nameStart > 0) {
					name = line.substring(nameStart);
				} else if (pathStart > 0) {
					name = line.substring(pathStart);
				}
				if (name != null) {
					// code, libraries and file backed mappings never hold the game state
					if (name.startsWith("/system") || name.startsWith("/apex")
							|| name.startsWith("/vendor") || name.startsWith("/product")
							|| name.startsWith("/data/app") || name.startsWith("/data/dalvik-cache")
							|| name.startsWith("/memfd:jit") || name.contains(".so")
							|| name.contains(".odex") || name.contains(".vdex")
							|| name.contains(".art") || name.contains(".oat")
							|| name.contains("[vvar]") || name.contains("[vsyscall]")
							|| name.contains("[vectors]")) {
						continue;
					}
				}
				boolean isDalvik = name != null && (name.contains("dalvik") || name.contains("[heap]"));
				(isDalvik ? dalvik : other).add(new long[]{start, end});
			}
		} catch (IOException e) {
			Log.e(TAG, "read maps failed", e);
		}
		dalvik.addAll(other);
		return dalvik;
	}

	public interface Listener {
		void onProgress(long scanned, long total);

		boolean isCancelled();
	}
}
