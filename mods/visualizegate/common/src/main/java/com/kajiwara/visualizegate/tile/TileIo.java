package com.kajiwara.visualizegate.tile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ㉗ タイルのディスク入出力 (純 Java・MC 非依存)。 配置 = {@code <baseDir>/<dimSanitized>/<tileX>_<tileZ>.bin}。
 * ファイルは<b>自己記述的</b> (ヘッダに {@code dimId,tileX,tileZ} を持つ) ＝ {@link #loadAll} がディレクトリ走査
 * だけで全タイルを (sanitize で失われる元 dimId も含め) 復元できる。 書込みは tmp→ATOMIC_MOVE。
 *
 * <p>クライアントは {@code baseDir = configDir/visualizegate/tiles/<worldId>}、 サーバーは
 * {@code baseDir = getWorldPath(ROOT)/visualizegate/tiles} を渡す (worldId はサーバーでは不要＝per-world)。
 */
public final class TileIo {

    private static final Logger LOG = LoggerFactory.getLogger("visualizegate");

    private TileIo() {
    }

    /** dimId ("minecraft:the_nether") をファイル名安全に (元 dimId はヘッダが保持＝可逆性不要)。 */
    public static String sanitizeDim(String dimId) {
        StringBuilder sb = new StringBuilder(dimId.length());
        for (int i = 0; i < dimId.length(); i++) {
            char c = dimId.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return sb.toString();
    }

    public static Path tilePath(Path baseDir, TileKey k) {
        return baseDir.resolve(sanitizeDim(k.dimId()))
                .resolve(k.tileX() + "_" + k.tileZ() + ".bin");
    }

    /** 1 タイルを atomic に書く (ヘッダ {@link TileKey} + 本体 {@link TileCodec})。 */
    public static void writeTile(Path baseDir, TileKey k, Tile tile) throws IOException {
        Path f = tilePath(baseDir, k);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeUTF(k.dimId());
            out.writeInt(k.tileX());
            out.writeInt(k.tileZ());
            TileCodec.encodeTo(out, tile);
        }
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 1 タイルを読む (ヘッダの座標は無視し、 引数 {@code k} の Tile として返す)。 無ければ null。 */
    public static Tile readTile(Path baseDir, TileKey k) throws IOException {
        Path f = tilePath(baseDir, k);
        if (!Files.exists(f)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(f)))) {
            in.readUTF();  // dimId (ヘッダ・既知なので読み飛ばす)
            in.readInt();  // tileX
            in.readInt();  // tileZ
            return TileCodec.decodeFrom(in);
        }
    }

    /**
     * {@code baseDir} 配下の全 {@code *.bin} を走査し、 ヘッダから {@link TileKey} を復元して {@code store} へ
     * 設置 (= 前セッション以前を含む全探索範囲をメモリへ・旧 json の全ロードと等価)。 個々の破損ファイルは
     * 警告して飛ばす (起動/解析を止めない)。 戻り値=ロードしたタイル数。
     */
    public static int loadAll(Path baseDir, TileStore store) {
        if (!Files.isDirectory(baseDir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> walk = Files.walk(baseDir)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".bin")).toList();
            for (Path f : files) {
                try (DataInputStream in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(f)))) {
                    String dimId = in.readUTF();
                    int tx = in.readInt();
                    int tz = in.readInt();
                    Tile t = TileCodec.decodeFrom(in);
                    store.putTile(new TileKey(dimId, tx, tz), t);
                    n++;
                } catch (Exception ex) {
                    LOG.warn("[visualizegate] tile load skip {} ({})", f, ex.toString());
                }
            }
        } catch (IOException ex) {
            LOG.warn("[visualizegate] tile dir walk failed {} ({})", baseDir, ex.toString());
        }
        return n;
    }

    /** dirty なタイルだけを書き出し、 dirty をクリア。 最初の致命的失敗で停止 (dirty は残す)。 */
    public static void writeDirty(Path baseDir, TileStore store) throws IOException {
        List<TileKey> keys = store.dirtyTiles();
        for (TileKey k : keys) {
            Tile t = store.getTile(k);
            if (t != null && !t.isEmpty()) {
                writeTile(baseDir, k, t);
            }
        }
        store.clearDirty();
    }
}
