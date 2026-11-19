package com.hyflip.server.source.hypixel;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader for Minecraft's binary NBT format, enough to decode Hypixel {@code item_bytes}
 * (base64 of gzipped NBT). Compounds become {@code Map<String, Object>}, lists become
 * {@code List<Object>}, numbers keep their Java type.
 */
public final class Nbt {

    private static final int MAX_DEPTH = 64;

    private Nbt() {
    }

    /** Decodes base64 + gzip + NBT and returns the root compound. */
    public static Map<String, Object> readBase64Gzip(String base64) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(base64);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return read(in);
        }
    }

    /** Reads an uncompressed NBT document whose root is a named compound. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int type = in.readUnsignedByte();
        if (type != 10) {
            throw new IOException("NBT root is not a compound (type " + type + ")");
        }
        in.readUTF(); // root name
        return (Map<String, Object>) readPayload(in, type, 0);
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested too deep");
        }
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] bytes = new byte[length(in)];
                in.readFully(bytes);
                yield bytes;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int n = length(in);
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                int tag;
                while ((tag = in.readUnsignedByte()) != 0) {
                    String name = in.readUTF();
                    map.put(name, readPayload(in, tag, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int[] values = new int[length(in)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readInt();
                }
                yield values;
            }
            case 12 -> {
                long[] values = new long[length(in)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readLong();
                }
                yield values;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 1_000_000) {
            throw new IOException("Bad NBT length " + n);
        }
        return n;
    }
}
