package net.portaltiertagger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.portaltiertagger.PortalTierTagger;
import net.portaltiertagger.config.ModConfig;
import net.portaltiertagger.network.RankingEntry;

import java.awt.Color;
import java.util.Map;

public class TierTaggerCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, 
                                 CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("tiertagger")
            .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                ModConfig config = PortalTierTagger.getConfig();
                if (config != null) {
                    config.enabled = !config.enabled;
                    if (PortalTierTagger.getConfigManager() != null) PortalTierTagger.getConfigManager().save();
                    ctx.getSource().sendFeedback(Text.literal("Tier tags: ")
                        .append(Text.literal(config.enabled ? "ENABLED" : "DISABLED")
                            .formatted(config.enabled ? Formatting.GREEN : Formatting.RED)));
                }
                return 1;
            }))

            .then(ClientCommandManager.literal("refresh").executes(ctx -> {
                PortalTierTagger.triggerRefresh();
                ctx.getSource().sendFeedback(Text.literal("Refreshing rankings...").formatted(Formatting.YELLOW));
                return 1;
            }))

            .then(ClientCommandManager.literal("cache")
                .then(ClientCommandManager.literal("clear").executes(ctx -> {
                    PortalTierTagger.getCache().clear();
                    ctx.getSource().sendFeedback(Text.literal("Cache cleared.").formatted(Formatting.GREEN));
                    return 1;
                }))
                .then(ClientCommandManager.literal("size").executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("Cache size: " + PortalTierTagger.getCache().size() + " players").formatted(Formatting.AQUA));
                    return 1;
                }))
            )

            // === UPDATED RANK COMMAND ===
            .then(ClientCommandManager.literal("rank")
                .then(ClientCommandManager.argument("player", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "player");
                        RankingEntry entry = PortalTierTagger.getCache().get(name);
                        
                        if (entry != null && entry.getAllTiers() != null && !entry.getAllTiers().isEmpty()) {
                            ModConfig config = PortalTierTagger.getConfig();
                            
                            // Send header
                            ctx.getSource().sendFeedback(Text.literal("")
                                .append(Text.literal(name + "'s Tiers:").formatted(Formatting.GOLD, Formatting.BOLD)));
                            
                            // Loop through ALL tiers
                            for (Map.Entry<String, String> tierData : entry.getAllTiers().entrySet()) {
                                String gamemode = tierData.getKey();
                                String tier = tierData.getValue();
                                
                                // 1. Get the Emoji
                                Identifier fontId = Identifier.of("portal_tier_tagger", "default");
                                Style emojiStyle = Style.EMPTY.withFont(fontId);
                                MutableText emojiText = Text.literal(getGamemodeEmoji(gamemode) + " ").setStyle(emojiStyle);
                                
                                // 2. Get the Tier Color
                                int colorInt = 0xFFFFFF; // Default white
                                if (config != null) {
                                    String colorHex = config.getRankColor(tier);
                                    Color color = parseHex(colorHex);
                                    colorInt = (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
                                }
                                MutableText tierText = Text.literal("[" + tier + "] ").withColor(colorInt);
                                
                                // 3. Get the Gamemode Name
                                MutableText gamemodeText = Text.literal(gamemode).formatted(Formatting.GRAY);
                                
                                // 4. Combine and send
                                MutableText line = emojiText.append(tierText).append(gamemodeText);
                                ctx.getSource().sendFeedback(line);
                            }
                        } else {
                            ctx.getSource().sendFeedback(Text.literal(name + " is not ranked or not in cache.")
                                .formatted(Formatting.RED));
                        }
                        return 1;
                    })
                )
            )
        );
    }

    // Helper method to get emojis (Same as the renderer)
    private static String getGamemodeEmoji(String gamemode) {
        if (gamemode == null) return "";
        switch (gamemode.toLowerCase()) {
            case "mace": return "\uE001";
            case "sword": return "\uE002";
            case "axe": return "\uE003";
            case "smp": return "\uE004";
            case "uhc": return "\uE005";
            case "pot": return "\uE006";
            case "nethop": return "\uE007";
            case "vanilla": return "\uE008";
            default: return "";
        }
    }

    // Helper method to parse hex colors
    private static Color parseHex(String hex) {
        try {
            hex = hex.replace("#", "");
            if (hex.length() == 6) return new Color(Integer.parseInt(hex, 16));
        } catch (NumberFormatException ignored) {}
        return new Color(0xFFFFFF);
    }
}
