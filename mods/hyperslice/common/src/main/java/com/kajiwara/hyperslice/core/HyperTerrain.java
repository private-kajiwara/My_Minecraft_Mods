package com.kajiwara.hyperslice.core;

/**
 * 4 次元地形関数。 <b>全スライス共通の唯一の真実</b>。
 *
 * <p>シードとスライス枚数 {@code N} だけを状態に持つ純粋関数で、
 * {@code (x, y, z, w)} から地形を決定する。 スライスごとに別ロジックを持たせては
 * <b>ならない</b> — 相関が壊れると「4 次元世界を w で薄切りにしたもの」という
 * 前提そのものが成立しなくなる。
 *
 * <h2>w 方向の厳密な周期性</h2>
 * {@code w} は {@code 0 .. N-1} を巡回する仕様なので、 {@code w=N-1} と {@code w=0} が
 * 不連続だとそこが「壁」に見えて世界の一貫性が崩れる。 そこで w 方向の格子添字を
 * <b>整数演算のみ</b>で {@code N} 周期に畳み込んでいる ({@link #wLattice})。
 * 浮動小数の丸めを経由しないため {@code height(x,z,w) == height(x,z,w+N)} が
 * ビット単位で厳密に成立する (後付けが困難なので最初から組み込んである)。
 *
 * <h2>調整</h2>
 * 人間が触る数値は下の {@code ── 調整用定数 ──} ブロックに全て集約してある。
 * ここ以外にマジックナンバーを散らさないこと。
 */
public final class HyperTerrain {

    // =================================================================
    // ── 調整用定数 (人間が触るのはここだけ) ──
    // =================================================================

    /**
     * x/z 方向の主要周期 [ブロック]。 最も粗いオクターブの波長。
     * 小さくすると地形が細かく・大きくするとなだらかで雄大になる。
     */
    public static final double XZ_PERIOD = 27.0;

    /**
     * w 方向の格子間隔 [スライス]。 x/z より意図的に短くしてある。
     *
     * <p>これが「1 スライス動くとどれだけ地形が変わるか」を決める最重要ノブ。
     * 2.0 だと「同じ山だと分かるが尾根の形が変わる」程度。
     * 大きくすると隣接スライスがより似る (相関が強い)、
     * 小さくするとスライスごとに別世界に近づく。
     *
     * <p>実際の格子点数 K は {@code N} から導出される ({@link #wLatticeCount})。
     * N が小さくて K が 1 になると w 方向が定数化して 4 次元性が消えるため、
     * K は最低 2 に切り上げられる (例: N=2 のときは実効間隔 1 スライスになる)。
     */
    public static final double W_LATTICE_SPACING = 2.0;

    /**
     * w 方向のオクターブ毎の格子密度倍率。
     *
     * <p>{@code 1} = 全オクターブが同じ w 格子を使う。 大まかな山塊も細かい尾根も
     * 同じ速さで w 方向に変形するため「同じ山だと分かるが尾根の形が変わる」に
     * なりやすい (既定)。
     * <p>{@code 2} にすると高周波成分ほど w 方向に速く変化し、
     * 隣接スライスの細部が一気に無相関になる。
     */
    public static final int W_LACUNARITY = 1;

    /** オクターブ数。 増やすと細部が増えるが生成コストも上がる。 */
    public static final int OCTAVES = 4;

    /** オクターブ毎の振幅減衰。 小さいほどなだらか、 大きいほどゴツゴツする。 */
    public static final double PERSISTENCE = 0.5;

    /** オクターブ毎の x/z 周波数倍率。 */
    public static final int XZ_LACUNARITY = 2;

    /** 地形の基準高度 [ブロック]。 ノイズ 0 のときの地表。 */
    public static final double BASE_HEIGHT = 72.0;

    /** 地形の起伏の振幅 [ブロック]。 ノイズ ±1 が ±この値になる。 */
    public static final double HEIGHT_AMPLITUDE = 34.0;

    /** 海面高度 [ブロック]。 これ以下の空きは水で満たす。 */
    public static final int SEA_LEVEL = 63;

    /** 世界の最低高度 [ブロック]。 dimension_type の min_y と一致させること。 */
    public static final int MIN_Y = -64;

    /** 世界の高さ [ブロック]。 dimension_type の height と一致させること。 */
    public static final int WORLD_HEIGHT = 384;

    // =================================================================
    // ── 以降は実装 (通常は触らない) ──
    // =================================================================

    /** splitmix64 の finalizer で使う定数。 */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private final long seed;
    private final int sliceCount;
    /** 最も粗いオクターブにおける w 格子点数 K。 */
    private final int wLatticeCount;
    /** 全オクターブ振幅の総和 (出力を [-1,1] に正規化するため)。 */
    private final double amplitudeSum;

    /**
     * @param seed       ワールドシード
     * @param sliceCount スライス枚数 N (w の周期)。 1 以上。
     */
    public HyperTerrain(long seed, int sliceCount) {
        if (sliceCount < 1) {
            throw new IllegalArgumentException("sliceCount must be >= 1, got " + sliceCount);
        }
        this.seed = seed;
        this.sliceCount = sliceCount;
        this.wLatticeCount = wLatticeCount(sliceCount);

        double sum = 0.0;
        double amp = 1.0;
        for (int o = 0; o < OCTAVES; o++) {
            sum += amp;
            amp *= PERSISTENCE;
        }
        this.amplitudeSum = sum;
    }

    /**
     * スライス枚数 N から w 格子点数 K を導出する。
     *
     * <p>K が 1 だと w 方向が定数になり 4 次元性が消えるため、 N が 2 以上なら
     * K は最低 2 に切り上げる。 実効的な格子間隔は {@code N / K} スライス。
     */
    public static int wLatticeCount(int sliceCount) {
        if (sliceCount <= 1) {
            return 1;
        }
        return Math.max(2, (int) Math.round(sliceCount / W_LATTICE_SPACING));
    }

    public long seed() {
        return seed;
    }

    public int sliceCount() {
        return sliceCount;
    }

    // ── 公開クエリ ──────────────────────────────────────────────

    /**
     * スライス {@code w} における {@code (x,z)} 柱の地表高度。
     *
     * <p>この値 <b>以下</b> の y が固体、 それより上が空気 (または海面以下なら水)。
     */
    public int surfaceY(int x, int z, int w) {
        return surfaceY(x, z, (double) w);
    }

    /** {@link #surfaceY(int, int, int)} の連続 w 版。 整数 w では int 版と完全に一致する。 */
    public int surfaceY(int x, int z, double w) {
        double n = noise(x, z, w);
        return (int) Math.floor(BASE_HEIGHT + n * HEIGHT_AMPLITUDE);
    }

    /** {@code (x,y,z,w)} が固体地形かどうか。 */
    public boolean isSolid(int x, int y, int z, int w) {
        return isSolid(x, y, z, (double) w);
    }

    /** {@link #isSolid(int, int, int, int)} の連続 w 版。 */
    public boolean isSolid(int x, int y, int z, double w) {
        return y >= MIN_Y && y <= surfaceY(x, z, w);
    }

    /** {@link HyperCoord} 版の {@link #isSolid(int, int, int, int)}。 */
    public boolean isSolid(HyperCoord c) {
        return isSolid(c.x(), c.y(), c.z(), c.w());
    }

    /**
     * 正規化された地形ノイズ。 概ね {@code [-1, 1]}。
     *
     * <p>w について厳密に周期 {@link #sliceCount} を持つ。
     */
    public double noise(int x, int z, int w) {
        return noise(x, z, (double) w);
    }

    /**
     * {@link #noise(int, int, int)} の連続 w 版。
     *
     * <p><b>整数 w では int 版とビット単位で完全に同一の値を返す</b> (int 版は
     * この実装への委譲になっており、 実装は 1 本しかない)。 根拠は
     * {@link #octave(int, double, double, double, int)} の説明を参照。
     *
     * <p>周期性も整数版と同じく厳密で、 小数 w に対しても
     * {@code noise(x,z,w) == noise(x,z,w+N)} がビット単位で成立する。
     */
    public double noise(int x, int z, double w) {
        double total = 0.0;
        double amp = 1.0;
        int xzFreq = 1;
        int wFreq = 1;

        for (int o = 0; o < OCTAVES; o++) {
            double fx = x * xzFreq / XZ_PERIOD;
            double fz = z * xzFreq / XZ_PERIOD;
            total += amp * octave(o, fx, fz, w, wLatticeCount * wFreq);
            amp *= PERSISTENCE;
            xzFreq *= XZ_LACUNARITY;
            wFreq *= W_LACUNARITY;
        }
        return total / amplitudeSum;
    }

    // ── 値ノイズ本体 ────────────────────────────────────────────

    /**
     * 1 オクターブ分の 3D 値ノイズ (x, z は連続・w も連続・w 格子は周期 {@code wPeriod})。
     *
     * <h3>整数 w での厳密な同一性</h3>
     * w を {@code 整数部 wi + 小数部 f} に分け、 <b>整数部だけを整数演算で</b>
     * 周期に畳み込む。 {@code f == 0.0} のとき {@code f * wPeriod} は厳密に
     * {@code 0.0} なので {@code u} は {@code rem / (double) sliceCount} そのものに
     * 帰着し、 {@code u < 1} より {@code step == 0}・{@code w0 == base} となる。
     * すなわち小数 w 対応前の整数専用実装と <b>ビット単位で同一</b>の式に落ちる
     * (整数 w の経路が別実装として残っていないので乖離しようがない)。
     *
     * <h3>小数 w でも周期は厳密</h3>
     * {@code w} → {@code w + N} で {@code wi} が {@code N} 増え {@code scaled} が
     * {@code N * wPeriod} 増えるため、 {@code base} は {@code wPeriod} だけずれる
     * (= {@code mod wPeriod} で不変) 一方 {@code rem} と {@code f} は完全に一致する。
     * したがって浮動小数の丸めを経由せずに周期性が保たれる。
     *
     * @param wPeriod このオクターブにおける w 格子点数。 添字はこれで畳み込まれる。
     */
    private double octave(int octaveIndex, double fx, double fz, double w, int wPeriod) {
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = fade(fx - x0);
        double tz = fade(fz - z0);

        // w 格子: 整数部は整数演算で厳密に周期化する (下の wLattice を参照)。
        int wi = (int) Math.floor(w);
        double f = w - wi;                                  // [0, 1)
        long scaled = (long) wi * wPeriod;
        int base = (int) Math.floorDiv(scaled, sliceCount);
        long rem = Math.floorMod(scaled, (long) sliceCount);

        // 小数部はここでだけ効く。 f == 0.0 なら u == rem / sliceCount に厳密一致。
        double u = (rem + f * wPeriod) / (double) sliceCount;
        int step = (int) Math.floor(u);
        double tw = fade(u - step);
        int w0 = base + step;

        int w0m = Math.floorMod(w0, wPeriod);
        int w1m = Math.floorMod(w0 + 1, wPeriod);

        double c000 = grid(octaveIndex, x0, z0, w0m);
        double c100 = grid(octaveIndex, x0 + 1, z0, w0m);
        double c010 = grid(octaveIndex, x0, z0 + 1, w0m);
        double c110 = grid(octaveIndex, x0 + 1, z0 + 1, w0m);
        double c001 = grid(octaveIndex, x0, z0, w1m);
        double c101 = grid(octaveIndex, x0 + 1, z0, w1m);
        double c011 = grid(octaveIndex, x0, z0 + 1, w1m);
        double c111 = grid(octaveIndex, x0 + 1, z0 + 1, w1m);

        double x00 = lerp(c000, c100, tx);
        double x10 = lerp(c010, c110, tx);
        double x01 = lerp(c001, c101, tx);
        double x11 = lerp(c011, c111, tx);

        return lerp(lerp(x00, x10, tz), lerp(x01, x11, tz), tw);
    }

    /**
     * w の格子位置を <b>整数演算だけ</b>で求める (診断・テスト用に公開)。
     *
     * <p>{@code w} と {@code w + N} は {@code scaled} が {@code N * wPeriod} だけ
     * ずれるので、 商は {@code wPeriod} だけずれ ({@code mod wPeriod} で同一)、
     * 剰余は完全に一致する。 したがって浮動小数の丸めを一切経由せずに
     * <b>ビット単位で厳密な周期性</b>が保証される。
     *
     * @return {@code [格子添字 (mod wPeriod), 格子内の分子]} の 2 要素
     */
    public int[] wLattice(int w, int wPeriod) {
        long scaled = (long) w * wPeriod;
        int index = Math.floorMod((int) Math.floorDiv(scaled, sliceCount), wPeriod);
        int numerator = (int) Math.floorMod(scaled, (long) sliceCount);
        return new int[] { index, numerator };
    }

    /** 格子点のハッシュ値 {@code [-1, 1]}。 決定論的で、 状態を持たない。 */
    private double grid(int octaveIndex, int ix, int iz, int iw) {
        long h = seed;
        h = mix(h ^ ((long) octaveIndex * GOLDEN));
        h = mix(h ^ ((long) ix * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ ((long) iz * 0x165667B19E3779F9L));
        h = mix(h ^ ((long) iw * 0x27D4EB2F165667C5L));
        // 上位 53bit を [0,1) にしてから [-1,1) へ
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    /** splitmix64 finalizer。 */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 5 次の滑らか化 (1 階・2 階微分が格子点で連続 = 継ぎ目が見えない)。 */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
