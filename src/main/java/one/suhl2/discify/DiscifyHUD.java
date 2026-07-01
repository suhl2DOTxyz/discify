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
import one.suhl2.discify.util.LyricsUtil;
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
    private static volatile long lastUpdateTimestamp = 0;
    public static boolean isHidden = false;
    public static final Logger LOGGER = LogManager.getLogger("Discify");
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    private static final int HUD_WIDTH = 185;
    private static final int ART_SIZE = 45;
    private static final int ART_X = 5;
    private static final int ART_Y = 5;
    private static final int TEXT_GAP = 10;
    private static final int BG_NO_LYRICS = 48;
    private static final int BG_WITH_LYRICS = 72;

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

        int currentProgress = progressMS;
        if (one.suhl2.discify.util.SMTCUtil.isPlaying() && lastUpdateTimestamp > 0) {
            long elapsed = System.currentTimeMillis() - lastUpdateTimestamp;
            if (elapsed > 0 && elapsed < 2000) {
                currentProgress += elapsed;
            }
        }

        double percentProgress = 0;
        if (durationMS > 0) {
            percentProgress = (double) currentProgress / (double) durationMS;
            if (percentProgress < 0) percentProgress = 0;
            if (percentProgress > 1) percentProgress = 1;
        }

        int textStart;
        if (DiscifyConfig.drawCover && hudInfo[4] != null && !hudInfo[4].isEmpty()) {
            textStart = ART_X + ART_SIZE + TEXT_GAP;
        } else {
            textStart = 5;
        }
        int textOffset = textStart - 5;

        // Split name early to compute dynamic background height based on wrapping
        List<FormattedCharSequence> nameWrap = font.split(FormattedText.of(hudInfo[0]), HUD_WIDTH - textStart - 5);
        int yOffset = 0;
        if (nameWrap.size() > 1) {
            yOffset = 15;
        }

        boolean showLyrics = DiscifyConfig.showLyrics && LyricsUtil.hasLyrics();
        int bgHeight = (showLyrics ? BG_WITH_LYRICS : BG_NO_LYRICS) + yOffset;

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(
                (DiscifyConfig.anchor == DiscifyConfig.Anchor.TOP_LEFT || DiscifyConfig.anchor == DiscifyConfig.Anchor.BOTTOM_LEFT)
                        ? DiscifyConfig.posX
                        : scaledWidth - HUD_WIDTH - DiscifyConfig.posX,
                (DiscifyConfig.anchor == DiscifyConfig.Anchor.TOP_LEFT || DiscifyConfig.anchor == DiscifyConfig.Anchor.TOP_RIGHT)
                        ? DiscifyConfig.posY
                        : scaledHeight - bgHeight - DiscifyConfig.posY);
        pose.scale((float) DiscifyConfig.scale, (float) DiscifyConfig.scale);

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
            context.blit(RenderPipelines.GUI_TEXTURED, albumImage.getIdentifier(), ART_X, ART_Y, 0F, 0F, size, size, size, size);
        }

        Color bgColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.backgroundColor);
        int bgArgb = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), DiscifyConfig.backgroundTransparency).getRGB();
        context.fill(0, 0, HUD_WIDTH, bgHeight, bgArgb);

        int titleColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.titleColor).getRGB();
        if (!nameWrap.isEmpty()) {
            if (nameWrap.size() > 1) {
                context.text(font, nameWrap.get(0), textStart, 5, titleColor, true);
                context.text(font, nameWrap.get(1), textStart, 18, titleColor, true);
            } else {
                context.text(font, nameWrap.get(0), textStart, 5, titleColor, true);
            }
        }

        int artistColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.artistColor).getRGB();
        String artistText = (hudInfo[1] != null) ? hudInfo[1] : "";
        List<FormattedCharSequence> artistWrap = font.split(FormattedText.of(artistText), HUD_WIDTH - textStart - 5);
        if (!artistWrap.isEmpty()) {
            context.text(font, artistWrap.get(0), textStart, 17 + yOffset, artistColor, true);
        }

        int progressY = 29 + yOffset;
        context.fill(textStart, progressY, HUD_WIDTH - 5, progressY + 2, MidnightColorUtil.hex2Rgb(DiscifyConfig.barColor).darker().darker().getRGB());
        int barWidth = HUD_WIDTH - 5 - textStart;
        context.fill(textStart, progressY, (int) (textStart + (barWidth * percentProgress)), progressY + 2, MidnightColorUtil.hex2Rgb(DiscifyConfig.barColor).getRGB());

        String volumeText = hudInfo[6] == null ? "" : hudInfo[6];
        String progressText = (currentProgress / (1000 * 60)) + ":" + String.format("%02d", (currentProgress / 1000 % 60));
        String durationText = (durationMS / (1000 * 60)) + ":" + String.format("%02d ", (durationMS / 1000 % 60))
                + I18n.get("discify.hud.volume") + ": " + volumeText;

        int timeY = 36 + yOffset;
        int timeColor = MidnightColorUtil.hex2Rgb(DiscifyConfig.timeColor).getRGB();
        context.text(font, progressText, textStart, timeY, timeColor, true);
        context.text(font, durationText, HUD_WIDTH - 5 - font.width(durationText), timeY, timeColor, true);

        if (showLyrics) {
            double smoothIndex = LyricsUtil.getSmoothIndex(currentProgress);
            if (smoothIndex >= -1.0) {
                int lyricsY = 48 + yOffset;
                List<LyricsUtil.LyricLine> lyrics = LyricsUtil.getCachedLyrics();

                int startIndex = (int) Math.floor(smoothIndex - 1);
                int endIndex = (int) Math.ceil(smoothIndex + 2);
                if (startIndex < 0) startIndex = 0;
                if (endIndex > lyrics.size()) endIndex = lyrics.size();

                for (int i = startIndex; i < endIndex; i++) {
                    LyricsUtil.LyricLine line = lyrics.get(i);
                    double diff = i - smoothIndex;

                    double alpha;
                    if (diff < 0) {
                        alpha = 1.0 + diff * 4.0;
                    } else if (diff <= 1.0) {
                        alpha = 1.0 - diff * 0.5;
                    } else {
                        alpha = 0.5 - (diff - 1.0) * 2.0;
                    }
                    if (alpha <= 0.0) continue;
                    if (alpha > 1.0) alpha = 1.0;

                    double y = lyricsY + diff * 12.0;

                    Color c = MidnightColorUtil.hex2Rgb(DiscifyConfig.lyricsColor);
                    int colorArgb = new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (alpha * 255)).getRGB();

                    List<FormattedCharSequence> lineWrap = font.split(FormattedText.of(line.text), HUD_WIDTH - textStart - 5);
                    if (!lineWrap.isEmpty()) {
                        context.text(font, lineWrap.get(0), textStart, (int) Math.round(y), colorArgb, true);
                    }
                }
            }
        }

        pose.popMatrix();
    }

    public static void updateData(String[] data) {
        hudInfo    = data;
        progressMS = hudInfo[2] == null ? 0 : (Integer.parseInt(hudInfo[2]) - 1000);
        durationMS = hudInfo[3] == null ? -1 : Integer.parseInt(hudInfo[3]);
        lastUpdateTimestamp = System.currentTimeMillis();
    }

    public static int getProgress() { return progressMS; }
    public static int getDuration() { return durationMS; }
    public static void setProgress(int progress) {
        progressMS = progress;
        lastUpdateTimestamp = System.currentTimeMillis();
    }
    public static void setDuration(int duration)  { durationMS = duration; }

    public static void increaseVolume() {
        EXECUTOR_SERVICE.execute(() -> SpotifyUtil.increaseVolume(DiscifyConfig.volumeStep));
    }

    public static void decreaseVolume() {
        EXECUTOR_SERVICE.execute(() -> SpotifyUtil.decreaseVolume(DiscifyConfig.volumeStep));
    }
}
