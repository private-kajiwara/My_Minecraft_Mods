package com.kajiwara.omnichest.search;

import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Chest Network Search が対象とするコンテナ種別。
 *
 * <p>
 * 「クライアントが正規に中身を読めるブロック」だけを列挙する設計とし、
 * 将来 MOD コンテナを追加する場合は {@link #fromBlockState(BlockState)} を拡張すれば良い。
 *
 * <p>
 * DOUBLE_CHEST / DOUBLE_TRAPPED_CHEST は、ラージチェスト (= 隣接した 2 ブロック) を
 * 「論理的に 1 つのコンテナ」として扱うためのタグ。スナップショット側で
 * isDouble() を持って判定できるようにする。
 */
public enum ContainerType {
    CHEST("チェスト", Keys.CONTAINER_TYPE_CHEST, "Chest"),
    TRAPPED_CHEST("トラップ式チェスト", Keys.CONTAINER_TYPE_TRAPPED_CHEST, "Trapped Chest"),
    DOUBLE_CHEST("ラージチェスト", Keys.CONTAINER_TYPE_DOUBLE_CHEST, "Large Chest"),
    DOUBLE_TRAPPED_CHEST("ラージトラップ式チェスト",
            Keys.CONTAINER_TYPE_DOUBLE_TRAPPED_CHEST, "Large Trapped Chest"),
    BARREL("樽", Keys.CONTAINER_TYPE_BARREL, "Barrel"),
    SHULKER_BOX("シュルカーボックス", Keys.CONTAINER_TYPE_SHULKER_BOX, "Shulker Box"),
    /**
     * エンダーチェスト。 ブロック自体はワールドに設置されているが、 中身は
     * 「プレイヤー固有 (= ディメンション非依存)」 のストレージである点が他コンテナと異なる。
     * 中身はプレイヤーがエンダーチェストを開いた瞬間に {@code ChestMenu} 経由で観測する
     * (= 既存の収集パイプラインにそのまま乗る)。
     */
    ENDER_CHEST("エンダーチェスト", Keys.CONTAINER_TYPE_ENDER_CHEST, "Ender Chest"),

    // ─── Redstone 系インベントリブロック (= 5〜9 スロットの小型コンテナ) ─────────
    // 仕様: ChestMenu / ShulkerBoxMenu と同様、 プレイヤーが開いた瞬間に menu 経由で
    // 中身を観測する (= サーバ通信なしで client が正規に読める情報のみ使う既存方針を維持)。
    /** ホッパー (= 5 スロット)。 メニューは {@code HopperMenu}。 */
    HOPPER("ホッパー", Keys.CONTAINER_TYPE_HOPPER, "Hopper"),
    /** ディスペンサー (= 3x3 = 9 スロット)。 メニューは {@code DispenserMenu}。 */
    DISPENSER("ディスペンサー", Keys.CONTAINER_TYPE_DISPENSER, "Dispenser"),
    /** ドロッパー (= 3x3 = 9 スロット)。 メニューはディスペンサーと同じ {@code DispenserMenu}。 */
    DROPPER("ドロッパー", Keys.CONTAINER_TYPE_DROPPER, "Dropper"),
    /** クラフター (= 3x3 = 9 スロット)。 メニューは {@code CrafterMenu}。 */
    CRAFTER("クラフター", Keys.CONTAINER_TYPE_CRAFTER, "Crafter"),

    // ─── コンテナを持つエンティティ (= ブロックではなく移動体) ─────────────────
    // 中身の取得は既存ブロックと完全に同一: プレイヤーが開いた瞬間に menu 経由で観測する。
    // ブロックと異なるのは「同一性 = エンティティ UUID」 と「位置 = 毎フレーム追従」 のみ
    // (= {@link EntityLocator} / {@link ContainerSnapshot#entity()} が担う)。
    /** チェスト付きトロッコ (= {@code MinecartChest})。 メニューは {@code ChestMenu} (27)。 */
    CHEST_MINECART("チェスト付きトロッコ", Keys.CONTAINER_TYPE_CHEST_MINECART, "Minecart with Chest"),
    /** チェスト付きボート / イカダ (= {@code AbstractChestBoat})。 メニューは {@code ChestMenu} (27)。 */
    CHEST_BOAT("チェスト付きボート", Keys.CONTAINER_TYPE_CHEST_BOAT, "Boat with Chest"),
    /** チェストを積んだモブ (= ロバ / ラバ / ラマ / 行商ラマ)。 メニューは {@code HorseInventoryMenu}。 */
    MOB_CHEST("チェストを積んだモブ", Keys.CONTAINER_TYPE_MOB_CHEST, "Pack Animal"),

    OTHER("コンテナ", Keys.CONTAINER_TYPE_OTHER, "Container");

    private final String displayName;
    private final String translationKey;
    private final String englishFallback;

    ContainerType(String displayName, String translationKey, String englishFallback) {
        this.displayName = displayName;
        this.translationKey = translationKey;
        this.englishFallback = englishFallback;
    }

    /** 翻訳前提でない場面 (= 古い呼び出し側、 SearchResult.containerType().displayName() 等) のための raw 名。 */
    public String displayName() {
        return this.displayName;
    }

    /** 翻訳キー解決済みの {@link Component}。 */
    public Component displayComponent() {
        return OmniChestLocale.get(this.translationKey, this.englishFallback);
    }

    /** 翻訳キーで解決した String (描画前の format に組み込みたい時用)。 */
    public String displayString() {
        return OmniChestLocale.getString(this.translationKey, this.englishFallback);
    }

    public boolean isDouble() {
        return this == DOUBLE_CHEST || this == DOUBLE_TRAPPED_CHEST;
    }

    /**
     * 「コンテナを持つエンティティ」 (= トロッコ / ボート / モブ) の種別か。
     * <p>
     * true のとき、 そのスナップショットは {@link ContainerSnapshot#entity()} を持ち、
     * 同一性は {@code BlockPos} ではなくエンティティ UUID、 ワールド描画は毎フレーム追従になる。
     * ブロック専用ロジック ({@code fromBlockState} / ラージチェスト判定 / ブロック破壊 sweep) には
     * 一切掛からない (= 純粋な追加経路)。
     */
    public boolean isEntity() {
        return this == CHEST_MINECART || this == CHEST_BOAT || this == MOB_CHEST;
    }

    /**
     * BlockState から ContainerType を判定する。
     * 非サポートのブロックの場合は {@code null} を返す。
     */
    public static ContainerType fromBlockState(BlockState state) {
        if (state == null)
            return null;
        var block = state.getBlock();
        // TrappedChestBlock は ChestBlock を継承するため、先にトラップを判定する。
        if (block instanceof TrappedChestBlock) {
            ChestType ct = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
            return ct == ChestType.SINGLE ? TRAPPED_CHEST : DOUBLE_TRAPPED_CHEST;
        }
        if (block instanceof ChestBlock) {
            ChestType ct = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
            return ct == ChestType.SINGLE ? CHEST : DOUBLE_CHEST;
        }
        if (block instanceof BarrelBlock) {
            return BARREL;
        }
        if (block instanceof ShulkerBoxBlock) {
            return SHULKER_BOX;
        }
        // EnderChestBlock は AbstractChestBlock を継承するが ChestBlock ではないため、
        // 上の ChestBlock 分岐には掛からない。 専用分岐で明示的に判定する。
        if (block instanceof EnderChestBlock) {
            return ENDER_CHEST;
        }
        // ─── Redstone 系インベントリブロック ─────────────────────────────
        // 「DropperBlock extends DispenserBlock」 のため、 先に DropperBlock を判定しないと
        // ドロッパーが「ディスペンサー」 として誤分類される (= Java の instanceof 優先順位)。
        if (block instanceof HopperBlock) {
            return HOPPER;
        }
        if (block instanceof DropperBlock) {
            return DROPPER;
        }
        if (block instanceof DispenserBlock) {
            return DISPENSER;
        }
        if (block instanceof CrafterBlock) {
            return CRAFTER;
        }
        return null;
    }

    /**
     * BlockState がラージチェストの一部のとき、もう片方の BlockPos を返す。
     * シングルチェスト / 非チェストの場合は {@code null}。
     */
    public static BlockPos otherHalfOrNull(BlockGetter level, BlockPos pos, BlockState state) {
        if (state == null || !(state.getBlock() instanceof ChestBlock))
            return null;
        if (!state.hasProperty(ChestBlock.TYPE))
            return null;
        ChestType ct = state.getValue(ChestBlock.TYPE);
        if (ct == ChestType.SINGLE)
            return null;
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }
}
