package com.kajiwara.worldchange.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.kajiwara.worldchange.WorldChange;
import com.kajiwara.worldchange.core.WorldEntry;
import com.mojang.serialization.Dynamic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * saves フォルダの走査と level.dat 読取 (Minecraft API グルー)。 純粋ロジック ({@code core.*}) が扱える
 * {@link WorldEntry} へ写し替える薄い層。
 *
 * <p>シードは <b>遅延読込</b>: {@link #listEntries()} は名前/ロック/互換のみ (LevelSummary から軽量に取得)、
 * シードは {@link #readSeed(String)} で対象フォルダだけ level.dat を開いて読む (全 saves 走査を避ける)。
 */
public final class WorldCatalog {

    private WorldCatalog() {
    }

    /** saves 内の全ワールドを列挙する (シードは未読込 = {@link OptionalLong#empty()})。 失敗時は空リスト。 */
    public static List<WorldEntry> listEntries() {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource source = mc.getLevelSource();
        List<WorldEntry> out = new ArrayList<>();
        try {
            LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
            List<LevelSummary> summaries = source.loadLevelSummaries(candidates).join();
            for (LevelSummary s : summaries) {
                out.add(new WorldEntry(
                        s.getLevelId(),
                        s.getLevelName(),
                        OptionalLong.empty(),
                        s.isLocked(),
                        s.isCompatible()));
            }
        } catch (Exception ex) {
            WorldChange.LOGGER.warn("Failed to enumerate saved worlds", ex);
        }
        return out;
    }

    /**
     * 指定フォルダの level.dat からシードを読む。 読めなければ {@link OptionalLong#empty()}
     * (例: ロード中ワールド = セッションロックで開けない / 破損 / シード欄なし)。
     *
     * <p>{@code createAccess} は短時間セッションロックを取るため try-with-resources で必ず閉じる。
     */
    public static OptionalLong readSeed(String folderId) {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource source = mc.getLevelSource();
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(folderId)) {
            // level.dat (壊れていれば level.dat_old に fallback) を Dynamic で読む。
            //   26.1 base = getUnfixedDataTagWithFallback() / 旧世代 = getDataTagFallback() (stonecutter 一方向)。
            //   どちらも public 無引数。 (boolean) 版は両世代とも private なので使わない。
            Dynamic<?> tag = access.getUnfixedDataTagWithFallback();
            return seedFromLevelData(tag);
        } catch (Exception ex) {
            WorldChange.LOGGER.debug("Could not read seed for '{}': {}", folderId, ex.toString());
            return OptionalLong.empty();
        }
    }

    /**
     * level.dat の Dynamic からシードを取り出す。 取得メソッドが root を返すか "Data" 配下を返すかは版で
     * 揺れ得るため、 候補パス ({@code Data.WorldGenSettings.seed} と {@code WorldGenSettings.seed}) を順に試す。
     */
    private static OptionalLong seedFromLevelData(Dynamic<?> tag) {
        Optional<? extends Dynamic<?>> viaData =
                tag.get("Data").get("WorldGenSettings").get("seed").result();
        if (viaData.isPresent()) {
            return OptionalLong.of(viaData.get().asLong(0L));
        }
        Optional<? extends Dynamic<?>> viaRoot =
                tag.get("WorldGenSettings").get("seed").result();
        return viaRoot.map(d -> OptionalLong.of(d.asLong(0L))).orElse(OptionalLong.empty());
    }
}
