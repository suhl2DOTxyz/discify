package one.clownless.discify;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import one.clownless.discify.util.SMTCUtil;
import one.clownless.discify.util.SpotifyUtil;
import one.clownless.discify.util.WindowsVolumeUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.net.URI;

public class DiscifyClient implements ClientModInitializer {

    private static KeyMapping playKey;
    private static KeyMapping nextKey;
    private static KeyMapping prevKey;
    private static KeyMapping forceKey;
    private static KeyMapping addTrackKey;
    private static KeyMapping hideKey;
    private static KeyMapping increaseVolumeKey;
    private static KeyMapping decreaseVolumeKey;
    private static KeyMapping toggleInGameMusicKey;

    private boolean playKeyPrevState              = false;
    private boolean nextKeyPrevState              = false;
    private boolean prevKeyPrevState              = false;
    private boolean forceKeyPrevState             = false;
    private boolean addTrackKeyPrevState          = false;
    private boolean hideKeyPrevState              = false;
    private boolean increaseVolumeKeyPrevState    = false;
    private boolean decreaseVolumeKeyPrevState    = false;
    private boolean toggleInGameMusicKeyPrevState = false;

    private static Thread requestThread;

    public static final Logger LOGGER = LogManager.getLogger("Discify");

    private static final KeyMapping.Category DISCIY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(DiscifyMain.MOD_ID, "discify"));

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Discify] Successfully loaded");
        DiscifyConfig.init(DiscifyMain.MOD_ID, DiscifyConfig.class);

        DiscifyHUD hud = new DiscifyHUD();
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(DiscifyMain.MOD_ID, "spotify_hud"),
                hud);

        requestThread = new Thread(() -> {
            int volumePollCooldown = 0;

            while (true) {
                try {
                    Thread.sleep(1000);

                    if (Minecraft.getInstance().level == null) {
                        DiscifyHUD.setProgress(0);
                        DiscifyHUD.setDuration(-1);
                        continue;
                    }

                    boolean shouldPoll = DiscifyHUD.getDuration() <= DiscifyHUD.getProgress()
                            || DiscifyHUD.getDuration() < 0;

                    if (shouldPoll) {
                        String[] data = SMTCUtil.getMediaInfo();

                        if (data[0] != null) {
                            DiscifyHUD.updateData(data);
                        } else {
                            DiscifyHUD.updateData(new String[8]);
                        }
                    } else if (SMTCUtil.isPlaying()) {
                        DiscifyHUD.setProgress(DiscifyHUD.getProgress() + 1000);
                    }

                    if (DiscifyHUD.hudInfo[0] != null && --volumePollCooldown <= 0) {
                        volumePollCooldown = 10;
                        float vol = WindowsVolumeUtil.getSpotifyVolume();
                        if (vol >= 0) {
                            DiscifyHUD.hudInfo[6] = String.valueOf(Math.round(vol * 100));
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("[Discify] Polling thread error: " + e.getMessage());
                }
            }
        });
        requestThread.setName("Discify Thread");
        requestThread.setDaemon(true);
        requestThread.start();

        SpotifyUtil.initialize();

        prevKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.prev", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_4, DISCIY_CATEGORY));
        playKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.play", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_5, DISCIY_CATEGORY));
        nextKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_6, DISCIY_CATEGORY));
        addTrackKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.addTrack", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, DISCIY_CATEGORY));
        forceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.force", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_8, DISCIY_CATEGORY));
        hideKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.hide", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_9, DISCIY_CATEGORY));
        increaseVolumeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.increaseVolume", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_ADD, DISCIY_CATEGORY));
        decreaseVolumeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.decreaseVolume", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_SUBTRACT, DISCIY_CATEGORY));
        toggleInGameMusicKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discify.key.toggleInGameMusic", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_1, DISCIY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                playKeyHandler(playKey.isDown());
                nextKeyHandler(nextKey.isDown());
                prevKeyHandler(prevKey.isDown());
                forceKeyHandler(forceKey.isDown());
                addTrackKeyHandler(addTrackKey.isDown());
                hideKeyHandler(hideKey.isDown());
                increaseVolumeKeyHandler(increaseVolumeKey.isDown());
                decreaseVolumeKeyHandler(decreaseVolumeKey.isDown());
                toggleInGameMusicKeyHandler(toggleInGameMusicKey.isDown());
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommands.literal("sharetrack").executes(context -> {
                    var player = Minecraft.getInstance().player;
                    if (player == null) return 0;
                    if (DiscifyHUD.hudInfo[5] != null) {
                        player.sendSystemMessage(Component.literal(DiscifyHUD.hudInfo[5]));
                    } else {
                        player.sendSystemMessage(Component.literal(
                                "[Discify] No share URL (requires Spotify auth — press L to authorize)."));
                    }
                    return 0;
                })
            )
        );
    }

    public void playKeyHandler(boolean currPressState) {
        if (currPressState && !playKeyPrevState) {
            LOGGER.info("Play/Pause key pressed");
            SpotifyUtil.playPause();
        }
        playKeyPrevState = currPressState;
    }

    public void nextKeyHandler(boolean currPressState) {
        if (currPressState && !nextKeyPrevState) {
            LOGGER.info("Next Key Pressed");
            SpotifyUtil.nextSong();
        }
        nextKeyPrevState = currPressState;
    }

    public void prevKeyHandler(boolean currPressState) {
        if (currPressState && !prevKeyPrevState) {
            LOGGER.info("Previous Key Pressed");
            SpotifyUtil.prevSong();
        }
        prevKeyPrevState = currPressState;
    }

    public void addTrackKeyHandler(boolean currPressState) {
        if (currPressState && !addTrackKeyPrevState) {
            LOGGER.info("Add Track Key Pressed");
            if (SpotifyUtil.isAuthorized()) {
                SpotifyUtil.addTrack();
            } else {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                            "[Discify] Spotify login required to save tracks. Opening browser..."));
                }
                Util.getPlatform().openUri(URI.create(SpotifyUtil.authorize()));
            }
        }
        addTrackKeyPrevState = currPressState;
    }

    public void forceKeyHandler(boolean currPressState) {
        if (currPressState && !forceKeyPrevState) {
            LOGGER.info("Force Key Pressed");
            DiscifyHUD.setDuration(-2000);
        }
        forceKeyPrevState = currPressState;
    }

    public void hideKeyHandler(boolean currPressState) {
        if (currPressState && !hideKeyPrevState) {
            LOGGER.info("Hide Key Pressed");
            DiscifyHUD.isHidden = !DiscifyHUD.isHidden;
        }
        hideKeyPrevState = currPressState;
    }

    public void increaseVolumeKeyHandler(boolean currPressState) {
        if (currPressState && !increaseVolumeKeyPrevState) {
            LOGGER.info("Increase Volume Key Pressed");
            DiscifyHUD.increaseVolume();
        }
        increaseVolumeKeyPrevState = currPressState;
    }

    public void decreaseVolumeKeyHandler(boolean currPressState) {
        if (currPressState && !decreaseVolumeKeyPrevState) {
            LOGGER.info("Decrease Volume Key Pressed");
            DiscifyHUD.decreaseVolume();
        }
        decreaseVolumeKeyPrevState = currPressState;
    }

    public void toggleInGameMusicKeyHandler(boolean currPressState) {
        if (currPressState && !toggleInGameMusicKeyPrevState) {
            LOGGER.info("Toggle In-Game Music Key Pressed");
            SpotifyUtil.toggleInGameMusic();
        }
        toggleInGameMusicKeyPrevState = currPressState;
    }
}
