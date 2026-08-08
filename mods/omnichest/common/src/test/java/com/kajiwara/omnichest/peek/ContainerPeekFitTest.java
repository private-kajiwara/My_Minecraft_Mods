package com.kajiwara.omnichest.peek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kajiwara.omnichest.peek.ContainerPeekFit.Status;
import org.junit.jupiter.api.Test;

/**
 * {@link ContainerPeekFit} の単体テスト。
 *
 * <p>
 * ここで固定するのは 「ピークが嘘をつかないこと」 と 「ポップアップが画面外へ出ないこと」 の 2 点。
 * <ul>
 *   <li>スナップショットが無いのに {@code AVAILABLE} を返さない (= 中身の捏造をしない)</li>
 *   <li>エンダーチェストで収集設定が OFF なら 「未登録」 ではなく設定案内を返す
 *       (= 「一度開けば記録される」 という誤誘導をしない)</li>
 *   <li>あらゆる画面サイズ (= GUI スケール 1〜8 / Auto) でパネルが画面内に収まる</li>
 * </ul>
 */
class ContainerPeekFitTest {

    // ════════════════════════════════════════════════════════════════════
    // status
    // ════════════════════════════════════════════════════════════════════

    @Test
    void normalContainerWithSnapshotIsAvailable() {
        assertEquals(Status.AVAILABLE, ContainerPeekFit.status(false, true, true));
        // 非エンダーでは enderSearchEnabled は結果に影響しない。
        assertEquals(Status.AVAILABLE, ContainerPeekFit.status(false, false, true));
    }

    @Test
    void normalContainerWithoutSnapshotIsNotRecorded() {
        assertEquals(Status.NOT_RECORDED, ContainerPeekFit.status(false, true, false));
        assertEquals(Status.NOT_RECORDED, ContainerPeekFit.status(false, false, false));
    }

    @Test
    void enderChestWithSearchEnabledBehavesLikeAnyContainer() {
        assertEquals(Status.AVAILABLE, ContainerPeekFit.status(true, true, true));
        assertEquals(Status.NOT_RECORDED, ContainerPeekFit.status(true, true, false));
    }

    @Test
    void enderChestWithSearchDisabledReportsTheSettingNotNotRecorded() {
        // 「一度開くと記録します」 は OFF の間は嘘になるので、 設定案内を優先する。
        assertEquals(Status.ENDER_SEARCH_DISABLED, ContainerPeekFit.status(true, false, false));
        // 設定を切る前の残骸が残っていても、 今後更新されない事実を伝えるほうを選ぶ。
        assertEquals(Status.ENDER_SEARCH_DISABLED, ContainerPeekFit.status(true, false, true));
    }

    @Test
    void statusNeverClaimsAvailableWithoutASnapshot() {
        for (boolean ender : new boolean[] { false, true }) {
            for (boolean enabled : new boolean[] { false, true }) {
                Status s = ContainerPeekFit.status(ender, enabled, false);
                assertTrue(s != Status.AVAILABLE,
                        "snapshot 無しで AVAILABLE を返した: ender=" + ender + " enabled=" + enabled);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // pickLatestIndex (= エンダーチェストの座標非依存な共有)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void pickLatestReturnsMinusOneForEmpty() {
        assertEquals(-1, ContainerPeekFit.pickLatestIndex(null));
        assertEquals(-1, ContainerPeekFit.pickLatestIndex(new long[0]));
    }

    @Test
    void pickLatestReturnsTheMostRecentlySeen() {
        assertEquals(0, ContainerPeekFit.pickLatestIndex(new long[] { 100 }));
        assertEquals(2, ContainerPeekFit.pickLatestIndex(new long[] { 100, 200, 300 }));
        assertEquals(0, ContainerPeekFit.pickLatestIndex(new long[] { 300, 200, 100 }));
        assertEquals(1, ContainerPeekFit.pickLatestIndex(new long[] { 100, 300, 200 }));
    }

    @Test
    void pickLatestIsDeterministicOnTies() {
        // 同着は先に現れたほう = 走査順が同じなら結果も同じ (= 毎フレーム暴れない)。
        assertEquals(0, ContainerPeekFit.pickLatestIndex(new long[] { 500, 500, 500 }));
        assertEquals(1, ContainerPeekFit.pickLatestIndex(new long[] { 100, 500, 500 }));
    }

    // ════════════════════════════════════════════════════════════════════
    // gridColumns
    // ════════════════════════════════════════════════════════════════════

    @Test
    void vanillaContainersUseNineColumnsLikeTheRealGui() {
        assertEquals(9, ContainerPeekFit.gridColumns(27));  // チェスト / シュルカー / 樽
        assertEquals(9, ContainerPeekFit.gridColumns(54));  // ラージチェスト
    }

    @Test
    void columnsAlwaysStayInsideTheExistingPopupRange() {
        for (int n = -5; n <= 120; n++) {
            int c = ContainerPeekFit.gridColumns(n);
            assertTrue(c >= ContainerPeekFit.MIN_COLUMNS && c <= ContainerPeekFit.MAX_COLUMNS,
                    "slotCount=" + n + " → columns=" + c);
        }
    }

    @Test
    void smallContainersFitOnOneRowWhenPossible() {
        assertEquals(5, ContainerPeekFit.gridColumns(5));
        assertEquals(7, ContainerPeekFit.gridColumns(7));
        assertEquals(11, ContainerPeekFit.gridColumns(11));
        // 下限より小さい / 上限より大きい端数はクランプ。
        assertEquals(5, ContainerPeekFit.gridColumns(1));
        assertEquals(11, ContainerPeekFit.gridColumns(13));
    }

    // ════════════════════════════════════════════════════════════════════
    // popupX / popupY (= 画面内クランプ)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void popupIsHorizontallyCenteredOnTheCrosshairWhenItFits() {
        // 幅 640 の論理画面、 パネル幅 174 (= 9 列 Popup)、 クロスヘア中央 320。
        assertEquals(320 - 87, ContainerPeekFit.popupX(640, 174, 320));
    }

    @Test
    void popupIsPlacedJustBelowTheCrosshairWhenItFits() {
        // 高さ 360、 パネル高 140、 クロスヘア中央 180 → 180 + gap(12)。
        assertEquals(192, ContainerPeekFit.popupY(360, 140, 180));
    }

    @Test
    void popupFlipsAboveTheCrosshairWhenItWouldOverflowTheBottom() {
        // 下に置くと 180+12+140+4 = 336 > 320 なので上へ回す → 180 - 12 - 140 = 28。
        assertEquals(28, ContainerPeekFit.popupY(320, 140, 180));
    }

    @Test
    void popupNeverLeavesTheScreenAtAnyPlausibleGuiSize() {
        // Window#calculateScale により論理サイズは常に 320x240 以上 (Force Unicode 時のみ最悪 160x120)。
        // 念のためそれより狭い病的サイズまで含めて総当たりする。
        int[] widths = { 160, 200, 320, 427, 640, 854, 1280, 1920, 3840 };
        int[] heights = { 120, 150, 240, 320, 360, 480, 720, 1080, 2160 };
        int[] panelW = { 40, 120, 174, 210, 400, 1000 };
        int[] panelH = { 30, 80, 140, 220, 500 };
        for (int w : widths) {
            for (int h : heights) {
                for (int pw : panelW) {
                    for (int ph : panelH) {
                        int x = ContainerPeekFit.popupX(w, pw, w / 2);
                        int y = ContainerPeekFit.popupY(h, ph, h / 2);
                        String at = "gui=" + w + "x" + h + " panel=" + pw + "x" + ph;
                        assertTrue(x >= ContainerPeekFit.SCREEN_MARGIN, "左端が画面外: " + at + " x=" + x);
                        assertTrue(y >= ContainerPeekFit.SCREEN_MARGIN, "上端が画面外: " + at + " y=" + y);
                        // パネルが画面に収まるサイズなら、 右端 / 下端も必ず内側に入る。
                        if (pw + 2 * ContainerPeekFit.SCREEN_MARGIN <= w) {
                            assertTrue(x + pw <= w - ContainerPeekFit.SCREEN_MARGIN,
                                    "右端が画面外: " + at + " x=" + x);
                        }
                        if (ph + 2 * ContainerPeekFit.SCREEN_MARGIN <= h) {
                            assertTrue(y + ph <= h - ContainerPeekFit.SCREEN_MARGIN,
                                    "下端が画面外: " + at + " y=" + y);
                        }
                    }
                }
            }
        }
    }

    @Test
    void oversizedPanelsPreferTheTopLeftMarginRatherThanNegativeCoordinates() {
        // パネルが画面より大きい病的ケースでも負座標にしない (= 左上へ張り付けて右下へ流す)。
        assertEquals(ContainerPeekFit.SCREEN_MARGIN, ContainerPeekFit.popupX(200, 400, 100));
        assertEquals(ContainerPeekFit.SCREEN_MARGIN, ContainerPeekFit.popupY(150, 400, 75));
    }
}
