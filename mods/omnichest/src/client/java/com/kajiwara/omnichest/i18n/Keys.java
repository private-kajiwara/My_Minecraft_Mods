package com.kajiwara.omnichest.i18n;

/**
 * MOD 内で使用するすべての翻訳キーの定義。
 *
 * <p>
 * <b>命名規約</b>:
 * <pre>
 *   omnichest.&lt;group&gt;.&lt;subkey&gt;[.&lt;suffix&gt;]
 *
 *   group:   screen / button / tooltip / search / template / slot_lock /
 *            smart_storage / category / container_type / color_picker /
 *            toggle / preview / sort / config
 *   suffix:  tooltip ( = ホバー時の補足 ) など
 * </pre>
 *
 * <p>
 * Cloth Config 互換のため、 設定 GUI 側のキーは従来通り
 * {@code config.omnichest.*} 階層を維持している (= JSON も両方に対応する)。
 *
 * <p>
 * 「翻訳キーは文字列リテラルとして散らさない」ためにここに定数化する。
 * IDE の Find Usage で 「どこから引かれているか」を一望できる利点もある。
 */
public final class Keys {

    private Keys() {
    }

    // ─── 共通プレフィックス ─────────────────────────────────────
    public static final String PREFIX = "omnichest.";

    // ─── スクリーン タイトル ─────────────────────────────────────
    public static final String SCREEN_SEARCH_TITLE = "omnichest.screen.search.title";
    public static final String SCREEN_TEMPLATE_SAVE_TITLE = "omnichest.screen.template.save.title";
    public static final String SCREEN_TEMPLATE_MANAGER_TITLE = "omnichest.screen.template.manager.title";
    public static final String SCREEN_TEMPLATE_PREVIEW_TITLE = "omnichest.screen.template.preview.title";

    // ─── Mod Menu metadata (= MOD 一覧での説明文) ────────────────
    /**
     * Mod Menu が MOD 一覧の説明文として参照する翻訳キー。
     * <p>
     * Mod Menu は {@code modmenu.descriptionTranslation.<modid>} という固定キーが
     * 現在の言語に存在すれば、 {@code fabric.mod.json} の {@code description} より
     * 優先して表示する。 各 lang ファイルにこのキーを置くことで MOD 説明文を多言語化する。
     */
    public static final String MOD_DESCRIPTION = "modmenu.descriptionTranslation.omnichest";

    // ─── Reset 確認 Popup ──────────────────────────────────────
    public static final String RESET_POPUP_TITLE = "omnichest.reset_popup.title";
    /** 変更が 1 件も無いときの大見出し (= 「本当にリセット？」 ではなく「変更なし」 と知らせる)。 */
    public static final String RESET_POPUP_TITLE_NO_CHANGES = "omnichest.reset_popup.title_no_changes";
    public static final String RESET_POPUP_YES = "omnichest.reset_popup.yes";
    public static final String RESET_POPUP_NO = "omnichest.reset_popup.no";
    public static final String RESET_POPUP_NO_CHANGES = "omnichest.reset_popup.no_changes";
    /** 変更が無いときの Popup で唯一表示する「閉じる」 系ボタン。 */
    public static final String RESET_POPUP_BACK = "omnichest.reset_popup.back";

    // ─── ボタンラベル ──────────────────────────────────────────
    public static final String BUTTON_RESET = "omnichest.button.reset";
    public static final String BUTTON_SAVE = "omnichest.button.save";
    public static final String BUTTON_CANCEL = "omnichest.button.cancel";
    public static final String BUTTON_APPLY = "omnichest.button.apply";
    public static final String BUTTON_CLOSE = "omnichest.button.close";
    public static final String BUTTON_OK = "omnichest.button.ok";
    public static final String BUTTON_BACK = "omnichest.button.back";
    public static final String BUTTON_CREATE_NEW = "omnichest.button.create_new";

    public static final String BUTTON_DEPOSIT = "omnichest.button.deposit";
    public static final String BUTTON_COMPACT = "omnichest.button.compact";
    public static final String BUTTON_SEARCH_NETWORK = "omnichest.button.search_network";
    public static final String BUTTON_SAVE_TEMPLATE = "omnichest.button.save_template";
    public static final String BUTTON_APPLY_TEMPLATE = "omnichest.button.apply_template";
    public static final String BUTTON_MANAGE_TEMPLATES = "omnichest.button.manage_templates";
    public static final String BUTTON_SORT_BY_TYPE = "omnichest.button.sort_by_type";
    public static final String BUTTON_SORT_BY_COUNT = "omnichest.button.sort_by_count";
    public static final String BUTTON_LAYOUT_LEFT = "omnichest.button.layout_left";
    public static final String BUTTON_LAYOUT_RIGHT = "omnichest.button.layout_right";
    public static final String BUTTON_CATEGORY_SORT = "omnichest.button.category_sort";

    // ─── チェスト GUI ボタン Tooltip ──────────────────────────────
    /** ボタンが何をするのか、 ホバー時に説明する短い tooltip 群。 */
    public static final String BUTTON_DEPOSIT_TOOLTIP = "omnichest.button.deposit.tooltip";
    public static final String BUTTON_COMPACT_TOOLTIP = "omnichest.button.compact.tooltip";
    public static final String BUTTON_SEARCH_NETWORK_TOOLTIP = "omnichest.button.search_network.tooltip";
    public static final String BUTTON_SAVE_TEMPLATE_TOOLTIP = "omnichest.button.save_template.tooltip";
    public static final String BUTTON_APPLY_TEMPLATE_TOOLTIP = "omnichest.button.apply_template.tooltip";
    public static final String BUTTON_MANAGE_TEMPLATES_TOOLTIP = "omnichest.button.manage_templates.tooltip";
    public static final String BUTTON_SORT_BY_TYPE_TOOLTIP = "omnichest.button.sort_by_type.tooltip";
    public static final String BUTTON_SORT_BY_COUNT_TOOLTIP = "omnichest.button.sort_by_count.tooltip";
    public static final String BUTTON_LAYOUT_LEFT_TOOLTIP = "omnichest.button.layout_left.tooltip";
    public static final String BUTTON_LAYOUT_RIGHT_TOOLTIP = "omnichest.button.layout_right.tooltip";
    public static final String EDITBOX_SEARCH_TOOLTIP = "omnichest.editbox.search.tooltip";

    public static final String BUTTON_SORT_DISTANCE = "omnichest.button.sort_distance";
    public static final String BUTTON_SORT_COUNT = "omnichest.button.sort_count";
    public static final String BUTTON_SORT_NAME = "omnichest.button.sort_name";
    public static final String BUTTON_SEARCH_SELECTED = "omnichest.button.search_selected";
    public static final String BUTTON_CLEAR_SELECTION = "omnichest.button.clear_selection";
    public static final String BUTTON_SAVE_CURRENT_CHEST = "omnichest.button.save_current_chest";

    // ─── EditBox ヒント / ラベル ──────────────────────────────────
    public static final String EDITBOX_SEARCH_LABEL = "omnichest.editbox.search.label";
    public static final String EDITBOX_NAME_LABEL = "omnichest.editbox.name.label";

    public static final String EDITBOX_SEARCH_HINT_GENERIC = "omnichest.editbox.search.hint.generic";
    public static final String EDITBOX_SEARCH_HINT_NETWORK = "omnichest.editbox.search.hint.network";
    public static final String EDITBOX_SEARCH_HINT_TEMPLATE = "omnichest.editbox.search.hint.template";
    public static final String EDITBOX_TEMPLATE_NAME_HINT = "omnichest.editbox.template_name.hint";

    // ─── 検索画面 ───────────────────────────────────────────
    public static final String SEARCH_SUMMARY = "omnichest.search.summary";
    public static final String SEARCH_HINT = "omnichest.search.hint";
    /** 「ALT + W」: 表示中の全件を選択。 */
    public static final String SEARCH_HINT_SELECT_ALL = "omnichest.search.hint.select_all";
    /** 「ALT + S」: 選択をすべて解除。 */
    public static final String SEARCH_HINT_CLEAR_SELECTION = "omnichest.search.hint.clear_selection";
    /** 「ALT + D」: カーソル下の 1 行を選択解除 + ピンからも削除。 */
    public static final String SEARCH_HINT_DESELECT_HOVERED = "omnichest.search.hint.deselect_hovered";

    // ─── Category Sort ボタン Tooltip ────────────────────────────
    public static final String CATEGORY_SORT_TOOLTIP = "omnichest.button.category_sort.tooltip";

    // ─── Controls Help Panel (チェスト GUI の脇に出す操作方法一覧) ──────
    public static final String CONTROLS_TITLE = "omnichest.controls.title";
    public static final String CONTROLS_LINE_SLOT_LOCK_ALT_CLICK = "omnichest.controls.line.slot_lock_alt_click";
    public static final String CONTROLS_LINE_SLOT_LOCK_MIDDLE_CLICK = "omnichest.controls.line.slot_lock_middle_click";
    public static final String CONTROLS_LINE_ITEM_LOCK_CYCLE = "omnichest.controls.line.item_lock_cycle";
    public static final String CONTROLS_LINE_ALT_DRAG = "omnichest.controls.line.alt_drag";
    public static final String CONTROLS_LINE_SHIFT_COMPACT = "omnichest.controls.line.shift_compact";
    /** ALT を押しながらシュルカーボックスにホバーすると中身プレビューを出す機能の説明行。 */
    public static final String CONTROLS_LINE_ALT_HOVER_SHULKER_PREVIEW =
            "omnichest.controls.line.alt_hover_shulker_preview";

    // ─── テンプレート関連 ─────────────────────────────────────
    public static final String TEMPLATE_DEFAULT_NAME = "omnichest.template.default_name";
    public static final String TEMPLATE_UNTITLED = "omnichest.template.untitled";

    public static final String TEMPLATE_KIND_LABEL = "omnichest.template.kind.label";
    public static final String TEMPLATE_KIND_EXACT = "omnichest.template.kind.exact";
    public static final String TEMPLATE_KIND_CATEGORY = "omnichest.template.kind.category";
    public static final String TEMPLATE_KIND_HYBRID = "omnichest.template.kind.hybrid";

    public static final String TEMPLATE_KIND_BADGE_EXACT = "omnichest.template.kind.badge.exact";
    public static final String TEMPLATE_KIND_BADGE_CATEGORY = "omnichest.template.kind.badge.category";
    public static final String TEMPLATE_KIND_BADGE_HYBRID = "omnichest.template.kind.badge.hybrid";

    public static final String TEMPLATE_KIND_HELP_EXACT = "omnichest.template.kind.help.exact";
    public static final String TEMPLATE_KIND_HELP_CATEGORY = "omnichest.template.kind.help.category";
    public static final String TEMPLATE_KIND_HELP_HYBRID = "omnichest.template.kind.help.hybrid";

    public static final String TEMPLATE_MANAGER_SUMMARY = "omnichest.template.manager.summary";
    public static final String TEMPLATE_MANAGER_HINT = "omnichest.template.manager.hint";
    public static final String TEMPLATE_MANAGER_SLOT_RULE_COUNT = "omnichest.template.manager.slot_rule_count";

    public static final String TEMPLATE_ROW_ACTION_APPLY = "omnichest.template.row.action.apply";
    public static final String TEMPLATE_ROW_ACTION_DUPLICATE = "omnichest.template.row.action.duplicate";
    public static final String TEMPLATE_ROW_ACTION_DELETE = "omnichest.template.row.action.delete";
    public static final String TEMPLATE_ROW_ACTION_UP = "omnichest.template.row.action.up";
    public static final String TEMPLATE_ROW_ACTION_DOWN = "omnichest.template.row.action.down";

    public static final String TEMPLATE_PREVIEW_TITLE_FORMAT = "omnichest.template.preview.title_format";
    public static final String TEMPLATE_PREVIEW_TITLE_NULL = "omnichest.template.preview.title_null";
    public static final String TEMPLATE_PREVIEW_COMPUTING = "omnichest.template.preview.computing";
    public static final String TEMPLATE_PREVIEW_MOVES = "omnichest.template.preview.moves";
    public static final String TEMPLATE_PREVIEW_SHORTAGES = "omnichest.template.preview.shortages";
    public static final String TEMPLATE_PREVIEW_STRANDED = "omnichest.template.preview.stranded";
    public static final String TEMPLATE_PREVIEW_NOTHING_TO_DO = "omnichest.template.preview.nothing_to_do";
    public static final String TEMPLATE_PREVIEW_MOVE_ROW = "omnichest.template.preview.move_row";
    public static final String TEMPLATE_PREVIEW_MOVE_SWAP = "omnichest.template.preview.move_swap";
    public static final String TEMPLATE_PREVIEW_MORE_ITEMS = "omnichest.template.preview.more_items";

    // ─── Slot Lock ─────────────────────────────────────────
    public static final String SLOT_LOCK_TOOLTIP_LOCKED_SLOT = "omnichest.slot_lock.tooltip.locked_slot";
    public static final String SLOT_LOCK_TOOLTIP_LOCKED_ITEM = "omnichest.slot_lock.tooltip.locked_item";
    public static final String SLOT_LOCK_TOOLTIP_SESSION = "omnichest.slot_lock.tooltip.session";
    public static final String SLOT_LOCK_TOOLTIP_HOTBAR_DEFAULT = "omnichest.slot_lock.tooltip.default_hotbar";
    public static final String SLOT_LOCK_TOOLTIP_OFFHAND_DEFAULT = "omnichest.slot_lock.tooltip.default_offhand";
    public static final String SLOT_LOCK_TOOLTIP_ARMOR_DEFAULT = "omnichest.slot_lock.tooltip.default_armor";

    public static final String SLOT_LOCK_CHAT_TOGGLED = "omnichest.slot_lock.chat.toggled";
    public static final String SLOT_LOCK_CHAT_NOTHING_TO_CLEAR = "omnichest.slot_lock.chat.nothing_to_clear";
    public static final String SLOT_LOCK_CHAT_CLEARED = "omnichest.slot_lock.chat.cleared";
    public static final String SLOT_LOCK_CHAT_CONFIRM_CLEAR = "omnichest.slot_lock.chat.confirm_clear";

    // ─── Smart Storage / Auto Deposit ─────────────────────────────
    public static final String SMART_STORAGE_PREFIX = "omnichest.smart_storage.prefix";
    public static final String SMART_STORAGE_PLAN_EMPTY = "omnichest.smart_storage.plan_empty";
    public static final String SMART_STORAGE_PLAN_HEADER = "omnichest.smart_storage.plan_header";
    public static final String SMART_STORAGE_PLAN_LINE = "omnichest.smart_storage.plan_line";
    public static final String SMART_STORAGE_PLAN_LINE_NO_DESTINATION = "omnichest.smart_storage.plan_line_no_destination";
    public static final String SMART_STORAGE_NO_DESTINATION = "omnichest.smart_storage.no_destination";

    // ─── Category Badge ──────────────────────────────────────
    public static final String CATEGORY_BADGE_CONFIDENCE = "omnichest.category_badge.confidence";
    public static final String CATEGORY_BADGE_LOCK = "omnichest.category_badge.lock";
    /** 手動割り当てカテゴリの目印 (= 予測ではなくプレイヤーが決めたことを示す)。 */
    public static final String CATEGORY_BADGE_MANUAL = "omnichest.category_badge.manual";

    // ─── ContainerType displayName ─────────────────────────────
    public static final String CONTAINER_TYPE_CHEST = "omnichest.container_type.chest";
    public static final String CONTAINER_TYPE_TRAPPED_CHEST = "omnichest.container_type.trapped_chest";
    public static final String CONTAINER_TYPE_DOUBLE_CHEST = "omnichest.container_type.double_chest";
    public static final String CONTAINER_TYPE_DOUBLE_TRAPPED_CHEST = "omnichest.container_type.double_trapped_chest";
    public static final String CONTAINER_TYPE_BARREL = "omnichest.container_type.barrel";
    public static final String CONTAINER_TYPE_SHULKER_BOX = "omnichest.container_type.shulker_box";
    public static final String CONTAINER_TYPE_ENDER_CHEST = "omnichest.container_type.ender_chest";
    public static final String CONTAINER_TYPE_HOPPER = "omnichest.container_type.hopper";
    public static final String CONTAINER_TYPE_DISPENSER = "omnichest.container_type.dispenser";
    public static final String CONTAINER_TYPE_DROPPER = "omnichest.container_type.dropper";
    public static final String CONTAINER_TYPE_CRAFTER = "omnichest.container_type.crafter";
    // ─── コンテナを持つエンティティ (= トロッコ / ボート / モブ) ─────────────
    public static final String CONTAINER_TYPE_CHEST_MINECART = "omnichest.container_type.chest_minecart";
    public static final String CONTAINER_TYPE_CHEST_BOAT = "omnichest.container_type.chest_boat";
    public static final String CONTAINER_TYPE_MOB_CHEST = "omnichest.container_type.mob_chest";
    public static final String CONTAINER_TYPE_OTHER = "omnichest.container_type.other";

    // ─── StorageCategory displayName ───────────────────────────
    public static final String STORAGE_CATEGORY_PREFIX = "omnichest.storage_category.";

    // ─── ItemCategory displayName ─────────────────────────────
    public static final String ITEM_CATEGORY_PREFIX = "omnichest.item_category.";

    // ─── Search UI 拡張 (Category Tabs / Display Mode / Favorites) ─────
    /** カテゴリタブ ラベル prefix。 サフィックスは {@code SearchCategory#key()}。 */
    public static final String SEARCH_CATEGORY_PREFIX = "omnichest.search_category.";

    /** 表示モード ラベル prefix。 サフィックスは {@code ItemDisplayMode#key()}。 */
    public static final String SEARCH_DISPLAY_MODE_PREFIX = "omnichest.search.display_mode.";

    public static final String SEARCH_DISPLAY_MODE_LABEL = "omnichest.search.display_mode.label";
    public static final String SEARCH_FAVORITES_ADD_TOOLTIP = "omnichest.search.favorites.add_tooltip";
    public static final String SEARCH_FAVORITES_REMOVE_TOOLTIP = "omnichest.search.favorites.remove_tooltip";
    public static final String SEARCH_FAVORITES_EMPTY = "omnichest.search.favorites.empty";

    // ─── Toggle ON / OFF ─────────────────────────────────────
    public static final String TOGGLE_ON = "omnichest.toggle.on";
    public static final String TOGGLE_OFF = "omnichest.toggle.off";

    // ─── Color Picker ─────────────────────────────────────
    public static final String COLOR_PICKER_TITLE = "omnichest.color_picker.title";
    public static final String COLOR_PICKER_RGB_LINE = "omnichest.color_picker.rgb_line";
    public static final String COLOR_PICKER_HEX_LINE = "omnichest.color_picker.hex_line";
    public static final String COLOR_PICKER_OK = "omnichest.color_picker.ok";
    public static final String COLOR_PICKER_CANCEL = "omnichest.color_picker.cancel";

    // ─── Keybinds ─────────────────────────────────────────
    public static final String KEYBIND_OPEN_SEARCH = "key.omnichest.open_search";
    public static final String KEYBIND_SMART_DEPOSIT = "key.omnichest.smart_deposit";
    public static final String KEYBIND_TOGGLE_SLOT_LOCK = "key.omnichest.toggle_slot_lock";
    public static final String KEYBIND_CLEAR_ALL_SLOT_LOCKS = "key.omnichest.clear_all_slot_locks";

    public static final String KEYBIND_LINE_OPEN_SEARCH = "omnichest.keybind.line.open_search";
    public static final String KEYBIND_LINE_SMART_DEPOSIT = "omnichest.keybind.line.smart_deposit";
    public static final String KEYBIND_LINE_TOGGLE_SLOT_LOCK = "omnichest.keybind.line.toggle_slot_lock";
    public static final String KEYBIND_LINE_CLEAR_ALL_LOCKS = "omnichest.keybind.line.clear_all_locks";

    // ─── Config GUI / Language ──────────────────────────────
    public static final String CONFIG_CATEGORY_LANGUAGE = "config.omnichest.category.language";
    public static final String CONFIG_LANGUAGE_OVERRIDE = "config.omnichest.language.override";
    public static final String CONFIG_LANGUAGE_OVERRIDE_TOOLTIP = "config.omnichest.language.override.tooltip";
    public static final String CONFIG_LANGUAGE_INTRO = "config.omnichest.language.intro";
    public static final String CONFIG_LANGUAGE_RESTART_HINT = "config.omnichest.language.restart_hint";

    // RTL / Unicode 設定 (Language タブ内)
    public static final String CONFIG_LANGUAGE_RTL_MODE = "config.omnichest.language.rtl_mode";
    public static final String CONFIG_LANGUAGE_RTL_MODE_TOOLTIP = "config.omnichest.language.rtl_mode.tooltip";
    public static final String CONFIG_LANGUAGE_RTL_AUTO = "config.omnichest.language.rtl_mode.auto";
    public static final String CONFIG_LANGUAGE_RTL_FORCE_ON = "config.omnichest.language.rtl_mode.force_on";
    public static final String CONFIG_LANGUAGE_RTL_FORCE_OFF = "config.omnichest.language.rtl_mode.force_off";
    public static final String CONFIG_LANGUAGE_UNICODE_FONT_SAFETY = "config.omnichest.language.unicode_font_safety";
    public static final String CONFIG_LANGUAGE_UNICODE_FONT_SAFETY_TOOLTIP = "config.omnichest.language.unicode_font_safety.tooltip";

    // 16 new language names (Config Selector の現地語表示)
    public static final String LANGUAGE_EN_GB = "omnichest.language.en_gb";
    public static final String LANGUAGE_FR_FR = "omnichest.language.fr_fr";
    public static final String LANGUAGE_RU_RU = "omnichest.language.ru_ru";
    public static final String LANGUAGE_PT_BR = "omnichest.language.pt_br";
    public static final String LANGUAGE_TR_TR = "omnichest.language.tr_tr";
    public static final String LANGUAGE_AR_SA = "omnichest.language.ar_sa";
    public static final String LANGUAGE_HE_IL = "omnichest.language.he_il";
    public static final String LANGUAGE_HI_IN = "omnichest.language.hi_in";
    public static final String LANGUAGE_TH_TH = "omnichest.language.th_th";
    public static final String LANGUAGE_VI_VN = "omnichest.language.vi_vn";
    public static final String LANGUAGE_PL_PL = "omnichest.language.pl_pl";
    public static final String LANGUAGE_NL_NL = "omnichest.language.nl_nl";
    public static final String LANGUAGE_SV_SE = "omnichest.language.sv_se";
    public static final String LANGUAGE_DA_DK = "omnichest.language.da_dk";
    public static final String LANGUAGE_NB_NO = "omnichest.language.nb_no";
    public static final String LANGUAGE_FI_FI = "omnichest.language.fi_fi";
    public static final String LANGUAGE_CS_CZ = "omnichest.language.cs_cz";
    public static final String LANGUAGE_HU_HU = "omnichest.language.hu_hu";
    public static final String LANGUAGE_RO_RO = "omnichest.language.ro_ro";
    public static final String LANGUAGE_UK_UA = "omnichest.language.uk_ua";
    public static final String LANGUAGE_ID_ID = "omnichest.language.id_id";
    public static final String LANGUAGE_MS_MY = "omnichest.language.ms_my";

    // ─── Config GUI Sidebar Group (カテゴリ見出し) ───────────────
    public static final String CONFIG_GROUP_BASICS = "config.omnichest.group.basics";
    public static final String CONFIG_GROUP_ITEM_SORTING = "config.omnichest.group.item_sorting";
    public static final String CONFIG_GROUP_PROTECTION_SEARCH = "config.omnichest.group.protection_search";
    public static final String CONFIG_GROUP_APPEARANCE_INPUT = "config.omnichest.group.appearance_input";
}
