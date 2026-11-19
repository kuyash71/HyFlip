package com.hyflip.server.support;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Writes {@code item_bytes}-style NBT (base64 of gzipped NBT) for tests. Supports the types used. */
public final class NbtWriter {

    private NbtWriter() {
    }

    public static String itemBytes(String id, int count, Map<String, Object> extraAttributes) throws IOException {
        Map<String, Object> extra = new java.util.LinkedHashMap<>(extraAttributes);
        extra.put("id", id);
        Map<String, Object> item = Map.of("id", (short) 403, "Count", (byte) count,
                "tag", Map.of("ExtraAttributes", extra));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            writeCompound(out, Map.of("i", List.of(item)));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            out.writeByte(type(e.getValue()));
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue());
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        if (v instanceof Byte b) {
            out.writeByte(b);
        } else if (v instanceof Short s) {
            out.writeShort(s);
        } else if (v instanceof Integer i) {
            out.writeInt(i);
        } else if (v instanceof String s) {
            out.writeUTF(s);
        } else if (v instanceof Map<?, ?> m) {
            writeCompound(out, (Map<String, Object>) m);
        } else if (v instanceof List<?> l) {
            out.writeByte(l.isEmpty() ? 0 : type(l.get(0)));
            out.writeInt(l.size());
            for (Object o : l) {
                writePayload(out, o);
            }
        } else {
            throw new IllegalArgumentException("Unsupported " + v.getClass());
        }
    }

    private static int type(Object v) {
        if (v instanceof Byte) {
            return 1;
        } else if (v instanceof Short) {
            return 2;
        } else if (v instanceof Integer) {
            return 3;
        } else if (v instanceof String) {
            return 8;
        } else if (v instanceof List) {
            return 9;
        } else if (v instanceof Map) {
            return 10;
        }
        throw new IllegalArgumentException("Unsupported " + v.getClass());
    }
}
