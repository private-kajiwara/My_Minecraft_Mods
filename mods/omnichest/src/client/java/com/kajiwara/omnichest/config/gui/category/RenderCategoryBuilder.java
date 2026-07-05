package com.kajiwara.omnichest.config.gui.category;

import com.kajiwara.omnichest.config.data.RenderConfig;
import com.kajiwara.omnichest.config.gui.ConfigLabels;
import com.kajiwara.omnichest.config.gui.widget.TabBuilder;
import com.kajiwara.omnichest.config.gui.widget.TabModel;

/** 「Render / UI」タブの組み立て役。 */
public final class RenderCategoryBuilder {

    private RenderCategoryBuilder() {
    }

    public static TabModel build(RenderConfig cfg) {
        TabBuilder b = TabBuilder.start(ConfigLabels.category("render", "Render / UI"));

        b.toggle(ConfigLabels.entry("render.enableOverlay", "Enable Overlay"),
                cfg.enableOverlay, v -> cfg.enableOverlay = v, null);

        b.toggle(ConfigLabels.entry("render.showSelectedItemHud", "Selected Item HUD"),
                cfg.showSelectedItemHud, v -> cfg.showSelectedItemHud = v,
                ConfigLabels.tooltip("render.showSelectedItemHud",
                        "Show a read-only panel (top-left) with the selected item's name, "
                                + "total count and locations while playing. "
                                + "Appears/disappears together with the world pins."));

        b.color(ConfigLabels.entry("render.highlightColorRgb", "Highlight Color"),
                cfg.highlightColorRgb, v -> cfg.highlightColorRgb = v,
                ConfigLabels.tooltip("render.highlightColorRgb",
                        "Outline color used to highlight matched chests."));

        b.toggle(ConfigLabels.entry("render.guiAnimation", "GUI Animation"),
                cfg.guiAnimation, v -> cfg.guiAnimation = v, null);

        // ─── Main Menu Visibility (= チェスト GUI 上の各 OmniChest 要素の表示 ON/OFF) ───
        // すべて既定 ON。 OFF にしてもロジックは止めず、 そのウィジェットを描画しないだけ (= 表示のみ)。
        b.subHeader(ConfigLabels.sub("render.mainMenuVisibility", "Main Menu Visibility"), sub -> {
            sub.toggle(ConfigLabels.entry("render.showSearchBar", "Search Bar"),
                    cfg.showSearchBar, v -> cfg.showSearchBar = v,
                    ConfigLabels.tooltip("render.showSearchBar",
                            "Show the in-chest search bar that highlights matching items. "
                                    + "Hiding it does not stop search indexing."));

            sub.toggle(ConfigLabels.entry("render.showSortByType", "Type Button"),
                    cfg.showSortByType, v -> cfg.showSortByType = v,
                    ConfigLabels.tooltip("render.showSortByType",
                            "Show the \"Type\" sort button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showSortByCount", "Quantity Button"),
                    cfg.showSortByCount, v -> cfg.showSortByCount = v,
                    ConfigLabels.tooltip("render.showSortByCount",
                            "Show the \"Count\" sort button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showChestSearchButton", "Chest Search Button"),
                    cfg.showChestSearchButton, v -> cfg.showChestSearchButton = v,
                    ConfigLabels.tooltip("render.showChestSearchButton",
                            "Show the button that opens the warehouse search screen."));

            sub.toggle(ConfigLabels.entry("render.showCategorySortButton", "Category Sort Button"),
                    cfg.showCategorySortButton, v -> cfg.showCategorySortButton = v,
                    ConfigLabels.tooltip("render.showCategorySortButton",
                            "Show the category-sort button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showDepositButton", "Deposit Button"),
                    cfg.showDepositButton, v -> cfg.showDepositButton = v,
                    ConfigLabels.tooltip("render.showDepositButton",
                            "Show the deposit-matching button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showCompactButton", "Compact Button"),
                    cfg.showCompactButton, v -> cfg.showCompactButton = v,
                    ConfigLabels.tooltip("render.showCompactButton",
                            "Show the compact button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showTemplateButtons", "Template Buttons"),
                    cfg.showTemplateButtons, v -> cfg.showTemplateButtons = v,
                    ConfigLabels.tooltip("render.showTemplateButtons",
                            "Show the Save / Apply / Manage template buttons."));

            sub.toggle(ConfigLabels.entry("render.showSetCategoryButton", "Set Category Button"),
                    cfg.showSetCategoryButton, v -> cfg.showSetCategoryButton = v,
                    ConfigLabels.tooltip("render.showSetCategoryButton",
                            "Show the Set Category button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showAutoSortButton", "Category Auto Sort Button"),
                    cfg.showAutoSortButton, v -> cfg.showAutoSortButton = v,
                    ConfigLabels.tooltip("render.showAutoSortButton",
                            "Show the Category Auto Sort button in the chest screen."));

            sub.toggle(ConfigLabels.entry("render.showCategoryIndicator", "Category Indicator"),
                    cfg.showCategoryIndicator, v -> cfg.showCategoryIndicator = v,
                    ConfigLabels.tooltip("render.showCategoryIndicator",
                            "Show the category badge above the chest. "
                                    + "Hiding it does not stop classification."));

            sub.toggle(ConfigLabels.entry("render.showPredictionDisplay", "Prediction Display"),
                    cfg.showPredictionDisplay, v -> cfg.showPredictionDisplay = v,
                    ConfigLabels.tooltip("render.showPredictionDisplay",
                            "Show the confidence / manual marker on the category badge."));

            sub.toggle(ConfigLabels.entry("render.showControlsHelp", "Controls Help"),
                    cfg.showControlsHelp, v -> cfg.showControlsHelp = v,
                    ConfigLabels.tooltip("render.showControlsHelp",
                            "Show the controls quick-reference panel beside the chest."));
        });

        return b.build();
    }
}
