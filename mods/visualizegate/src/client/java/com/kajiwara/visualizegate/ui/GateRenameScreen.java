package com.kajiwara.visualizegate.ui;

import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
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

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w = 200;
        addRenderableWidget(new StringWidget(cx - w / 2, cy - 44, w, 12, this.title, this.font));
        editBox = new EditBox(this.font, cx - w / 2, cy - 22, w, 20,
                Component.translatable("visualizegate.gates.rename.hint"));
        editBox.setMaxLength(NAME_MAX);
        editBox.setHint(Component.translatable("visualizegate.gates.rename.hint"));
        editBox.setValue(seed);
        editBox.setCursorPosition(seed.length());
        editBox.setHighlightPos(0);       // 全選択 (Windows 風＝タイプで即置換)
        editBox.setCanLoseFocus(false);   // 金床と同じ sticky フォーカス (IME 維持)
        addRenderableWidget(editBox);
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.rename.save"), b -> commit())
                .bounds(cx - 102, cy + 6, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.rename.cancel"), b -> onClose())
                .bounds(cx + 2, cy + 6, 100, 20).build());
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
        // 半透明 dim (背後のワールドを暗く) ＋ 小パネル。
        g.fill(0, 0, this.width, this.height, 0xA0000000);
        int cx = this.width / 2;
        int cy = this.height / 2;
        int pw = 224;
        int ph = 92;
        int px = cx - pw / 2;
        int py = cy - ph / 2;
        g.fill(px, py, px + pw, py + ph, GateColors.PANEL);
        g.fill(px, py, px + pw, py + 1, GateColors.MAIN);
        g.fill(px, py + ph - 1, px + pw, py + ph, GateColors.MAIN);
        super.extractRenderState(g, mouseX, mouseY, partialTick); // widgets (title/editBox/buttons)
    }
}
