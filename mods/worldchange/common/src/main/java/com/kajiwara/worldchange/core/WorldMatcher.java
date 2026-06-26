package com.kajiwara.worldchange.core;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code "名前:シード"} クエリを saves 一覧 ({@link WorldEntry}) に突き合わせ、 一意なワールドを決める純粋ロジック。
 *
 * <p>方針 (決定 A: 名前主・シードは曖昧回避/緩い検証):
 * <ol>
 *   <li><b>名前で候補抽出</b>: フォルダ名一致を優先し、 無ければ表示名一致 (両方 trim・大小無視)。</li>
 *   <li>候補 0 → {@link WorldMatch.Status#NOT_FOUND}。</li>
 *   <li>候補 1:
 *     <ul>
 *       <li>シード未指定 → {@link WorldMatch.Status#MATCHED}。</li>
 *       <li>シード指定・候補のシード既知で一致 → MATCHED。</li>
 *       <li>シード指定・候補のシード既知で不一致 → {@link WorldMatch.Status#SEED_MISMATCH}
 *           (候補を採用しつつ警告)。 シード未読込 (不明) なら一致扱い (MATCHED)。</li>
 *     </ul>
 *   </li>
 *   <li>候補複数:
 *     <ul>
 *       <li>シード未指定 → {@link WorldMatch.Status#AMBIGUOUS_NEED_SEED}。</li>
 *       <li>シード指定 → シード既知で一致する候補だけに絞る。 ちょうど 1 件 → MATCHED。
 *           0 件 or 2 件以上 → {@link WorldMatch.Status#AMBIGUOUS_SEED_UNRESOLVED}。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>シードによる絞り込みが要るのは「候補複数」のときだけなので、 client 層は名前候補を出してから
 * その候補のシードだけ遅延読込すれば足りる (全 saves のシード走査を避ける)。
 */
public final class WorldMatcher {

    private WorldMatcher() {
    }

    /** 名前で候補を抽出する (フォルダ名一致を優先、 無ければ表示名一致)。 入力順を保つ。 */
    public static List<WorldEntry> nameCandidates(List<WorldEntry> entries, String name) {
        List<WorldEntry> byFolder = new ArrayList<>();
        List<WorldEntry> byDisplay = new ArrayList<>();
        for (WorldEntry e : entries) {
            if (e.matchesFolder(name)) {
                byFolder.add(e);
            } else if (e.matchesDisplay(name)) {
                byDisplay.add(e);
            }
        }
        return !byFolder.isEmpty() ? byFolder : byDisplay;
    }

    /**
     * クエリと「シードが (必要なら) 読み込まれた名前候補」から最終結果を導く。
     * {@code candidates} は {@link #nameCandidates} の出力で、 シード指定時は各候補の seed が
     * 読み込まれていること (未読込は「不明」として一致側に倒す)。
     */
    public static WorldMatch resolve(WorldQuery query, List<WorldEntry> candidates) {
        if (candidates.isEmpty()) {
            return new WorldMatch(WorldMatch.Status.NOT_FOUND, null, List.of());
        }
        OptionalLong wantSeed = query.seed();

        if (candidates.size() == 1) {
            WorldEntry only = candidates.get(0);
            if (wantSeed.isEmpty() || only.seed().isEmpty()) {
                return new WorldMatch(WorldMatch.Status.MATCHED, only, candidates);
            }
            boolean same = only.seed().getAsLong() == wantSeed.getAsLong();
            return new WorldMatch(same ? WorldMatch.Status.MATCHED : WorldMatch.Status.SEED_MISMATCH,
                    only, candidates);
        }

        // 候補複数。
        if (wantSeed.isEmpty()) {
            return new WorldMatch(WorldMatch.Status.AMBIGUOUS_NEED_SEED, null, candidates);
        }
        List<WorldEntry> seedHits = new ArrayList<>();
        for (WorldEntry e : candidates) {
            if (e.seed().isPresent() && e.seed().getAsLong() == wantSeed.getAsLong()) {
                seedHits.add(e);
            }
        }
        if (seedHits.size() == 1) {
            return new WorldMatch(WorldMatch.Status.MATCHED, seedHits.get(0), candidates);
        }
        return new WorldMatch(WorldMatch.Status.AMBIGUOUS_SEED_UNRESOLVED, null, candidates);
    }

    /** 名前候補抽出 → 解決をまとめて行う (候補が既に必要なシードを持つ前提・主にテスト用)。 */
    public static WorldMatch match(WorldQuery query, List<WorldEntry> entries) {
        return resolve(query, nameCandidates(entries, query.name()));
    }
}
