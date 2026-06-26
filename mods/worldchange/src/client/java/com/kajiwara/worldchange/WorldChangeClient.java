package com.kajiwara.worldchange;

import com.kajiwara.worldchange.command.WorldChangeCommands;
import com.kajiwara.worldchange.world.WorldSwitcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * クライアント側エントリポイント。
 *
 * <ul>
 *   <li>{@link WorldSwitcher#register()} — 切替の teardown 完了監視 tick。</li>
 *   <li>{@link WorldChangeCommands#register()} — {@code /worldChange} コマンド。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class WorldChangeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        WorldSwitcher.register();
        WorldChangeCommands.register();
        WorldChange.LOGGER.info("WorldChange client initialized (/worldChange command + world switcher).");
    }
}
