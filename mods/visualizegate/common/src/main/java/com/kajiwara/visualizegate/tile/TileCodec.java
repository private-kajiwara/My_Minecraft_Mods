package com.kajiwara.visualizegate.tile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ㉗ {@link Tile} ⇔ バイナリ (純 Java・MC 非依存)。 点数が大きいため JSON でなくコンパクトなバイナリ。
 *
 * <p>形式 (big-endian): {@code int schemaVersion, int lodStride, int count,
 * count × (int wx, int wz, int y, int color)}。 ボクセルキーは絶対座標から復元できるので {@code (wx,wz,y)} を保存。
 * ストリーム版 ({@link #encodeTo}/{@link #decodeFrom}) を {@link TileIo} がヘッダ ({@link TileKey}) と連結して使う
 * (= ファイルが自己記述的になり、 ディレクトリ走査だけで全タイルを復元できる)。
 */
public final class TileCodec {

    public static final int SCHEMA_VERSION = 1;

    private TileCodec() {
    }

    public static void encodeTo(DataOutputStream out, Tile tile) throws IOException {
        out.writeInt(SCHEMA_VERSION);
        out.writeInt(tile.lodStride());
        out.writeInt(tile.size());
        for (Map.Entry<Long, Long> e : tile.voxels().entrySet()) {
            long key = e.getKey();
            long val = e.getValue();
            out.writeInt(VoxelKey.x(key));
            out.writeInt(VoxelKey.z(key));
            out.writeInt(VoxelKey.valY(val));
            out.writeInt(VoxelKey.valColor(val));
        }
    }

    public static Tile decodeFrom(DataInputStream in) throws IOException {
        int schema = in.readInt();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("unsupported tile schema " + schema);
        }
        int lodStride = in.readInt();
        int count = in.readInt();
        if (count < 0 || count > 10_000_000) {
            throw new IOException("implausible tile voxel count " + count);
        }
        HashMap<Long, Long> voxels = new HashMap<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            int wx = in.readInt();
            int wz = in.readInt();
            int y = in.readInt();
            int color = in.readInt();
            voxels.put(VoxelKey.of(wx, wz, y), VoxelKey.packVal(color, y));
        }
        return new Tile(voxels, lodStride);
    }

    public static byte[] encode(Tile tile) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(tile.size() * 16 + 16);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            encodeTo(out, tile);
        } catch (IOException e) {
            throw new RuntimeException("tile encode failed", e); // ByteArrayOutputStream は実質投げない
        }
        return bos.toByteArray();
    }

    public static Tile decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return decodeFrom(in);
        }
    }
}
