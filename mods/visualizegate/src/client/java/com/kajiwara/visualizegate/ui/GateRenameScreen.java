package com.kajiwara.visualizegate.ui;

import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * ゲート名変更の小さな専用ポップアップ (金床 {@code AnvilScreen} と同じ init/フォーカス確立パターン)。
 *
 * <p>金床の肝は <b>{@code setInitialFocus()} を override して EditBox を強制フォーカス</b>する点。 素の
 * {@code Screen.setInitialFocus()} は<b>最後の入力がキーボードの時だけ</b>フォーカスするため、 マウス
 * (ダブルクリック) で開くと EditBox がフォーカスされず {@code onTextInputFocusChange(true)} が走らず IME
 * (変換中文字/予測変換) が有効化されなかった。 ここでは金床同様 init 経路で確実にフォーカス＝IME-aware を確立し、
 * preedit は<b>既定経路</b> ({@code Screen→getFocused().preeditUpdated}) で EditBox にインライン表示される
 * (preedit/PreeditEvent を一切参照しない＝全ノード成立。 26.1+ は vanilla がインライン配信・1.21.x は OS 変換で金床同条件)。
 *
 * <p>確定 (Enter / 保存) ＝ {@link PortalMemory#setName} で永続して親画面へ戻る。 取消 (Esc / キャンセル / onClose)
 * ＝変更せず親へ戻る。 親 ({@code PointCloudScreen}) インスタンスを保持して戻すのでリスト/スクロール/選択は保たれる。
 */
public class GateRenameScreen extends Screen {

    private static final int NAME_MAX = 24; // PointCloudScreen の旧インラインと同値

    // ── レイアウト確定案 (MC GUI px・init と描画で computeLayout() を共用) ──
    private static final int CONTENT_W = 200;   // タイトル/EditBox/ボタン行の内容幅
    private static final int PAD = 14;          // パネル内パディング (上下左右)
    private static final int PANEL_W = CONTENT_W + PAD * 2; // 228
    // 上 14 + タイトル 9 + 間隔 10 + EditBox 20 + 間隔 10 + ボタン 20 + 下 14 = 97
    private static final int PANEL_H = 97;
    private static final int BTN_GAP = 4;       // 保存/キャンセル間の間隔
    private int panelX;
    private int panelY;
    private int contentX;
    private int titleY;
    private int editY;
    private int buttonY;

    private final Screen parent;
    private final PortalDimension dim;
    private final int gx;
    private final int gy;
    private final int gz;
    private final String seed;          // 開始時の表示名 (ユーザー命名＞既定 OW-/N-n)
    private final boolean wasDefault;   // seed が既定名だったか (無変更なら永続しない)
    private EditBox editBox;

    public GateRenameScreen(Screen parent, PortalDimension dim, int x, int y, int z, int number) {
        super(Component.translatable("visualizegate.rename.title"));
        this.parent = parent;
        this.dim = dim;
        this.gx = x;
        this.gy = y;
        this.gz = z;
        String userName = PortalMemory.get().nameAt(dim, x, y, z);
        this.wasDefault = (userName == null);
        this.seed = (userName != null) ? userName
                : ((dim == PortalDimension.NETHER ? "N-" : "OW-") + number);
    }

    /** レイアウト確定案を算出 (init・描画で共用)。 パネルは画面中央・内容は contentX 起点・幅 {@value #CONTENT_W} に整列。 */
    private void computeLayout() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        contentX = panelX + PAD;
        titleY = panelY + PAD;          // 上パディング後
        editY = titleY + 9 + 10;        // タイトル(9) + 間隔10
        buttonY = editY + 20 + 10;      // EditBox(20) + 間隔10
    }

    @Override
    protected void init() {
        computeLayout();
        editBox = new EditBox(this.font, contentX, editY, CONTENT_W, 20,
                Component.translatable("visualizegate.gates.rename.hint"));
        editBox.setMaxLength(NAME_MAX);
        editBox.setHint(Component.translatable("visualizegate.gates.rename.hint"));
        editBox.setValue(seed);
        editBox.setCursorPosition(seed.length());
        editBox.setHighlightPos(0);       // 全選択 (Windows 風＝タイプで即置換)
        editBox.setCanLoseFocus(false);   // 金床と同じ sticky フォーカス (IME 維持)
        addRenderableWidget(editBox);
        // 保存/キャンセルを横並びで合計 200 (= フィールド幅) にぴったり整列。
        int bw = (CONTENT_W - BTN_GAP) / 2; // 98
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.rename.save"), b -> commit())
                .bounds(contentX, buttonY, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.rename.cancel"), b -> onClose())
                .bounds(contentX + bw + BTN_GAP, buttonY, bw, 20).build());
    }

    /** 金床と同一: マウスで開いても EditBox を確実にフォーカス (= IME-aware を init 経路で確立)。 */
    @Override
    protected void setInitialFocus() {
        if (editBox != null) {
            this.setInitialFocus(editBox);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
            commit();
            return true;
        }
        return super.keyPressed(event); // Esc→onClose(取消)・他はフォーカス中の EditBox へ (preedit も既定経路で届く)
    }

    private void commit() {
        String v = editBox.getValue().trim();
        if (v.length() > NAME_MAX) {
            v = v.substring(0, NAME_MAX);
        }
        // 既定名のまま無変更なら永続しない (旧インライン確定と同仕様)。
        if (!(wasDefault && v.equals(seed))) {
            PortalMemory.get().setName(dim, gx, gy, gz, v); // 空→null (既定名へ)
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent); // 取消: 変更なし
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        computeLayout();
        // 半透明 dim (背後のワールドを暗く) ＋ 小パネル。
        g.fill(0, 0, this.width, this.height, 0xA0000000);
        int px2 = panelX + PANEL_W;
        int py2 = panelY + PANEL_H;
        g.fill(panelX, panelY, px2, py2, GateColors.PANEL);
        // ボーダー: 下/左/右 は通常 (暗め) で統一、 上端のみ明るい紫アクセント。
        g.fill(panelX, py2 - 1, px2, py2, GateColors.MAIN_DIM);          // 下
        g.fill(panelX, panelY, panelX + 1, py2, GateColors.MAIN_DIM);    // 左
        g.fill(px2 - 1, panelY, px2, py2, GateColors.MAIN_DIM);          // 右
        g.fill(panelX, panelY, px2, panelY + 1, GateColors.MAIN);        // 上端アクセント (明るい紫)
        // タイトル: 内容幅 (contentX..+CONTENT_W) の中央寄せ。
        int tw = this.font.width(this.title);
        g.text(this.font, this.title, contentX + (CONTENT_W - tw) / 2, titleY, GateColors.TEXT);
        super.extractRenderState(g, mouseX, mouseY, partialTick); // widgets (editBox/buttons)
    }
}
