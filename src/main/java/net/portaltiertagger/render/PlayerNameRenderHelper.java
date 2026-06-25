package net.portaltiertagger.render;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.portaltiertagger.PortalTierTagger;
import net.portaltiertagger.config.ModConfig;
import net.portaltiertagger.network.RankingEntry;

import java.awt.Color;

public class PlayerNameRenderHelper {

    // The correct resource path for the font JSON file:
    // assets/portal_tier_tagger/font/default.json
    // Minecraft resolves Identifier("portal_tier_tagger", "default") ->
    //   assets/portal_tier_tagger/font/default.json  (fonts are under /font/)
    // We verify it by checking for the JSON resource at the font path directly.
    private static final Identifier FONT_ID = Identifier.of("portal_tier_tagger", "default");
    private static final Identifier FONT_RESOURCE_PATH =
            Identifier.of("portal_tier_tagger", "font/default.json");

    // Cache whether the font loaded so we only log once per session
    private static Boolean fontAvailable = null;

    public static void renderTierTags(WorldRenderContext context) {
        ModConfig config = PortalTierTagger.getConfig();
        if (config == null) return;
        if (!config.enabled) return;
        if (config.displaySide == ModConfig.DisplaySide.DISABLED) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.world == null) return;

        // Check font availability once and log result
        if (fontAvailable == null) {
            fontAvailable = client.getResourceManager()
                    .getResource(FONT_RESOURCE_PATH)
                    .isPresent();
            if (fontAvailable) {
                PortalTierTagger.LOGGER.info("[PTT] Custom font loaded successfully: {}", FONT_ID);
            } else {
                PortalTierTagger.LOGGER.warn(
                        "[PTT] Custom font NOT found at {}. Falling back to plain text.",
                        FONT_RESOURCE_PATH);
            }
        }

        // BUG FIX #3: camera position must be subtracted from world coords
        // WorldRenderEvents.LAST MatrixStack starts at the origin (0,0,0 = camera).
        // We must translate by (worldPos - cameraPos), not by worldPos directly.
        Vec3d cameraPos = context.camera().getPos();

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider vcp = context.consumers();
        if (vcp == null) return;

        float tickDelta = client.getRenderTickCounter().getTickDelta(true);

        for (PlayerEntity player : client.world.getPlayers()) {
            if (player == client.player && !client.gameRenderer.getCamera().isThirdPerson()) continue;

            double distSq = player.squaredDistanceTo(cameraPos);
            double maxDist = config.distanceCutoff * config.distanceCutoff;
            if (distSq > maxDist) continue;

            if (!player.isAlive()) continue;
            if (player.isInvisibleTo(client.player)) continue;

            String key = player.getGameProfile().getName().toLowerCase();
            RankingEntry entry = PortalTierTagger.getCache().get(key);

            // Debug logging
            PortalTierTagger.LOGGER.debug(
                    "[PTT] Player={} cacheHit={} dist={}",
                    name, (entry != null), (int) Math.sqrt(distSq));

            if (entry == null) continue;

            RankingEntry.HighestTierResult targetTier = null;
            if (config.displayMode == ModConfig.DisplayMode.HIGHEST) {
                targetTier = entry.getHighestTier();
            } else if (config.displayMode == ModConfig.DisplayMode.LOWEST) {
                targetTier = entry.getLowestTier();
            }

            if (targetTier == null) continue;

            PortalTierTagger.LOGGER.debug(
                    "[PTT]   -> gamemode={} tier={}", targetTier.gamemode, targetTier.tier);

            renderTierTag(matrices, vcp, player, cameraPos,
                    targetTier.gamemode, targetTier.tier, config, tickDelta);
        }
    }

    private static void renderTierTag(MatrixStack matrices, VertexConsumerProvider vcp,
                                      PlayerEntity player, Vec3d cameraPos,
                                      String gamemode, String tier, ModConfig config,
                                      float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;

        // Build the display text
        MutableText finalText;

        if (Boolean.TRUE.equals(fontAvailable)) {
            // BUG FIX #2: apply emoji style correctly using withFont()
            Style emojiStyle = Style.EMPTY.withFont(FONT_ID);
            MutableText emojiText = Text.literal(getGamemodeEmoji(gamemode) + " ")
                    .setStyle(emojiStyle);

            String colorHex = config.getRankColor(tier);
            Color color = parseHex(colorHex);
            int colorInt = (0xFF << 24)
                    | (color.getRed() << 16)
                    | (color.getGreen() << 8)
                    | color.getBlue();

            MutableText tierText = Text.literal("[" + tier + "]").withColor(colorInt);
            finalText = emojiText.append(tierText);
        } else {
            // Plain-text fallback when font is unavailable
            String code = gamemode.substring(0, Math.min(3, gamemode.length())).toUpperCase();
            finalText = Text.literal("[" + code + "][" + tier + "]").withColor(0xAAAAAA);
        }

        // BUG FIX #3: compute CAMERA-RELATIVE position, not absolute world position.
        // player.prevX/prevY/prevZ + lerp toward current X/Y/Z gives the interpolated
        // world position. Subtracting cameraPos converts it to camera space, which is
        // what the WorldRenderEvents MatrixStack expects.
        double interpX = player.prevX + (player.getX() - player.prevX) * tickDelta;
        double interpY = player.prevY + (player.getY() - player.prevY) * tickDelta;
        double interpZ = player.prevZ + (player.getZ() - player.prevZ) * tickDelta;

        double relX = interpX - cameraPos.x;
        double relY = interpY - cameraPos.y;
        double relZ = interpZ - cameraPos.z;

        // Place the tag above the player's head (+ hitbox height + configurable offset)
        float yOffset = player.getHeight() + 0.25f + (float) config.offsetY;

        // Horizontal offset based on display side
        float scale = 0.025f;
        float textWidth = textRenderer.getWidth(finalText) * scale;
        float xOffset = 0f;
        if (config.displaySide == ModConfig.DisplaySide.LEFT) {
            xOffset = -textWidth - 0.15f;
        } else if (config.displaySide == ModConfig.DisplaySide.RIGHT) {
            xOffset = 0.15f;
        }

        matrices.push();

        // Translate to camera-relative position
        matrices.translate(relX, relY + yOffset, relZ);

        // Billboard: rotate to face the camera
        // getRotation() returns Quaternionf in 1.21.1 — no cast needed
        matrices.multiply(client.gameRenderer.getCamera().getRotation());

        // Scale: negative X flips the text right-side-up (MC renders upside-down by default)
        matrices.scale(-scale, -scale, scale);

        float drawX = -(textRenderer.getWidth(finalText) / 2.0f) + (xOffset / scale);

        textRenderer.draw(
                finalText,
                drawX,
                0.0f,
                0xFFFFFF,
                false,
                matrices.peek().getPositionMatrix(),
                vcp,
                TextRenderer.TextLayerType.SEE_THROUGH,  // visible through blocks like vanilla nametags
                0,
                15728880  // full brightness
        );

        matrices.pop();
    }

    private static String getGamemodeEmoji(String gamemode) {
        if (gamemode == null) return "";
        switch (gamemode.toLowerCase()) {
            case "mace":    return "\uE001";
            case "sword":   return "\uE002";
            case "axe":     return "\uE003";
            case "smp":     return "\uE004";
            case "uhc":     return "\uE005";
            case "pot":     return "\uE006";
            case "nethop":  return "\uE007";
            case "vanilla": return "\uE008";
            default:        return "";
        }
    }

    private static Color parseHex(String hex) {
        try {
            if (hex == null) return new Color(0xAAAAAA);
            hex = hex.replace("#", "");
            if (hex.length() == 6) {
                return new Color(Integer.parseInt(hex, 16));
            }
        } catch (NumberFormatException ignored) {}
        return new Color(0xAAAAAA);
    }

    /** Call this on world disconnect to re-check font on next join */
    public static void resetFontCache() {
        fontAvailable = null;
    }
}
