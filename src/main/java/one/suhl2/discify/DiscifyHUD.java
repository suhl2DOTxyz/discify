package one.suhl2.discify;

import net.minecraft.client.renderer.RenderPipelines;
import eu.midnightdust.lib.util.MidnightColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;
import one.suhl2.discify.util.SpotifyUtil;
import one.suhl2.discify.util.URLImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3x2fStack;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiscifyHUD implements HudElement {
    private static URLImage albumImage;
    public static String[] hudInfo;
    private static String prevImage;
    private static int progressMS;
    private static int durationMS;
    public static boolean isHidden = false;
    public static final Logger LOGGER = LogManager.getLogger("Discify");
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    public DiscifyHUD() {
        albumImage = null;
        hudInfo = new String[8];
        prevImage = "empty";
        progressMS = 0;
        durationMS = -1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, DeltaTracker tickDelta) {
        if (albumImage == null) {
            albumImage = new URLImage(300, 300);
        }

        if (hudInfo[0] == null || hudInfo[0].isEmpty() || isHidden) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.getDebugOverlay().showDebugScreen()) {
            return;
        }

        Font font = client.font;
        int scaledWidth  = client.getWindow().getGuiScaledWidth();
        int scaledHeight = client.getWindow().getGuiScaledHeight();

        double percentProgress = (double) progressMS / (double) durationMS;
        if (percentProgress < 0) {
            percentProgress = 0;
        }

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(
                (DiscifyConfig.anchor == DiscifyConfig.Anchor.TOP_LEFT || DiscifyConfig.anchor == DiscifyConfig.Anchor.BOTTOM_LEFT)
                        ? DiscifyConfig.posX
                        : scaledWidth - 185 - DiscifyConfig.posX,
                (DiscifyConfig.anchor == DiscifyConfig.Anchor.TOP_LEFT || DiscifyConfig.anchor == DiscifyConfig.Anchor.TOP_RIGHT)
                        ? DiscifyConfig.posY
                        : scaledHeight - 55 - DiscifyConfig.posY);
        pose.scale((float) DiscifyConfig.scale, (float) DiscifyConfig.scale);

        int textOffset = 55;
        if ((DiscifyConfig.drawCover) && hudInfo[4] != null && (!prevImage.equals(hudInfo[4]) && !hudInfo[4].equals(""))) {
            LOGGER.info("Drawing new album cover: " + hudInfo[4]);
            if (prevImage.startsWith("file:/")) {
                try {
                    java.io.File oldFile = new java.io.File(new java.net.URI(prevImage));
                    if (oldFile.exists()) {
                        oldFile.delete();
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Discify] Failed to delete old thumbnail: " + e.getMessage());
                }
            }
            albumImage.setImage(hudInfo[4]);
            prevImage = hudInfo[4];
        }
        if (hudInfo[4] != null && (DiscifyConfig.drawCover)) {
            int size = (int) (albumImage.getWidth() * 0.15F);
            context.blit(RenderPipelines.GUI_TEXTURED, albumImage.getIdentifier(), 5, 5, 0F, 0F, size, size, size, size);
            textOffset = 0;
        }

        Color bgColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.backgroundColor);
        int bgArgb = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), DiscifyConfig.backgroundTransparency).getRGB();
        context.fill(0, 0, 185 - textOffset, 55, bgArgb);
        context.fill(60 - textOffset, 48, 180 - textOffset, 50, MidnightColorUtil.hex2Rgb(DiscifyConfig.barColor).darker().darker().getRGB());
        context.fill(60 - textOffset, 48, (int) (60 + (120 * percentProgress)) - textOffset, 50, MidnightColorUtil.hex2Rgb(DiscifyConfig.barColor).getRGB());

        int titleColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.titleColor).getRGB();
        List<FormattedCharSequence> nameWrap = font.split(FormattedText.of(hudInfo[0]), 125);
        int yOffset = 0;
        if (!nameWrap.isEmpty()) {
            if (nameWrap.size() > 1) {
                context.text(font, nameWrap.get(0), 60 - textOffset, 5, titleColor, true);
                context.text(font, nameWrap.get(1), 60 - textOffset, 18, titleColor, true);
                yOffset = 15;
            } else {
                context.text(font, nameWrap.get(0), 60 - textOffset, 5, titleColor, true);
            }
        }

        pose.scale(0.5F, 0.5F);

        int artistColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.artistColor).getRGB();
        String artistText = (hudInfo[1] != null) ? hudInfo[1] : "";
        List<FormattedCharSequence> artistWrap = font.split(FormattedText.of(artistText), 140);
        if (!artistWrap.isEmpty()) {
            if (artistWrap.size() > 1) {
                context.text(font, artistWrap.get(0), 120 - (textOffset * 2), 44 + yOffset, artistColor, true);
                context.text(font, artistWrap.get(1), 120 - (textOffset * 2), 57 + yOffset, artistColor, true);
            } else {
                context.text(font, artistWrap.get(0), 120 - (textOffset * 2), 44 + yOffset, artistColor, true);
            }
        }

        String volumeText = hudInfo[6] == null ? "" : hudInfo[6];
        String progressText = (progressMS / (1000 * 60)) + ":" + String.format("%02d", (progressMS / 1000 % 60));
        String durationText = (durationMS / (1000 * 60)) + ":" + String.format("%02d ", (durationMS / 1000 % 60))
                + I18n.get("discify.hud.volume") + ": " + volumeText;

        int timeColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.timeColor).getRGB();
        context.text(font, progressText, 120 - (textOffset * 2), 85, timeColor, true);
        context.text(font, durationText, 360 - font.width(durationText) - (textOffset * 2), 85, timeColor, true);

        pose.popMatrix();
    }

    public static void updateData(String[] data) {
        hudInfo    = data;
        progressMS = hudInfo[2] == null ? 0 : (Integer.parseInt(hudInfo[2]) - 1000);
        durationMS = hudInfo[3] == null ? -1 : Integer.parseInt(hudInfo[3]);
    }

    public static int getProgress() { return progressMS; }
    public static int getDuration() { return durationMS; }
    public static void setProgress(int progress) { progressMS = progress; }
    public static void setDuration(int duration)  { durationMS = duration; }

    public static void increaseVolume() {
        EXECUTOR_SERVICE.execute(() -> SpotifyUtil.increaseVolume(DiscifyConfig.volumeStep));
    }

    public static void decreaseVolume() {
        EXECUTOR_SERVICE.execute(() -> SpotifyUtil.decreaseVolume(DiscifyConfig.volumeStep));
    }
}
