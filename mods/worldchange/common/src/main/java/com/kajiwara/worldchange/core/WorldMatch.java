package com.kajiwara.worldchange.core;

import java.util.List;
import java.util.Objects;

/**
 * {@link WorldMatcher} の照合結果 (MC 非依存)。
 *
 * @param status     照合の結末。
 * @param selected   一意に決まったワールド (status が {@link Status#MATCHED} / {@link Status#SEED_MISMATCH}
 *                   のときのみ非 null)。
 * @param candidates 曖昧時の候補一覧 (フィードバックでユーザーに提示する)。 一意決定時は selected 1 件、
 *                   不在時は空。
 */
public record WorldMatch(Status status, WorldEntry selected, List<WorldEntry> candidates) {

    public enum Status {
        /** 一意に決定 (シード一致 or シード未指定で同名 1 件)。 */
        MATCHED,
        /** 同名 1 件だがシードが不一致。 selected を採用しつつ警告する (非致命・緩い検証)。 */
        SEED_MISMATCH,
        /** 同名複数・シード未指定で一意化できない。 */
        AMBIGUOUS_NEED_SEED,
        /** 同名複数・シード指定ありだが一致/一意化できない。 */
        AMBIGUOUS_SEED_UNRESOLVED,
        /** 名前に一致するワールドが無い。 */
        NOT_FOUND
    }

    public WorldMatch {
        Objects.requireNonNull(status, "status");
        candidates = List.copyOf(candidates);
    }

    public boolean isResolved() {
        return selected != null;
    }
}
