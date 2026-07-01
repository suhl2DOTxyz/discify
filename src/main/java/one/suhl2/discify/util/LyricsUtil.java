package one.suhl2.discify.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsUtil {

    private static final Logger LOGGER = LogManager.getLogger("Discify");
    private static final String LRCLIB_API = "https://lrclib.net/api/get";
    private static final String LRCLIB_SEARCH_API = "https://lrclib.net/api/search";
    private static final String LRCMUX_API = "https://lrcmux.dev/api/compat/lrclib/api/get";

    private static volatile String currentSongKey = "";
    private static volatile List<LyricLine> cachedLyrics = Collections.emptyList();
    private static volatile boolean loading = false;

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)");

    public static void loadLyrics(String title, String artist) {
        String key = (title != null ? title : "") + "|||" + (artist != null ? artist : "");
        if (key.equals(currentSongKey)) return;
        currentSongKey = key;
        cachedLyrics = Collections.emptyList();
        loading = true;

        Thread fetcher = new Thread(() -> {
            int retries = 4; // Try up to 5 times total
            boolean success = false;

            while (retries >= 0 && !success) {
                try {
                    synchronized (LyricsUtil.class) {
                        if (!key.equals(currentSongKey)) return;
                    }

                    String encodedTitle = URLEncoder.encode(title != null ? title : "", StandardCharsets.UTF_8);
                    String encodedArtist = URLEncoder.encode(artist != null ? artist : "", StandardCharsets.UTF_8);
                    String url = LRCLIB_API + "?artist_name=" + encodedArtist + "&track_name=" + encodedTitle;

                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", "Discify-MCMod/1.1 (github.com/SUHL2/Discify)")
                            .timeout(java.time.Duration.ofSeconds(5))
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    synchronized (LyricsUtil.class) {
                        if (!key.equals(currentSongKey)) return;

                        if (response.statusCode() == 200) {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            if (json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull()) {
                                String syncedLyrics = json.get("syncedLyrics").getAsString();
                                cachedLyrics = parseLRC(syncedLyrics);
                                LOGGER.info("[Discify] Loaded " + cachedLyrics.size() + " synced lyric lines for \"" + title + "\"");
                            } else if (json.has("plainLyrics") && !json.get("plainLyrics").isJsonNull()) {
                                String plainLyrics = json.get("plainLyrics").getAsString();
                                cachedLyrics = parsePlain(plainLyrics);
                                LOGGER.info("[Discify] Loaded " + cachedLyrics.size() + " plain lyric lines for \"" + title + "\"");
                            }
                            success = true;
                        } else if (response.statusCode() == 404) {
                            LOGGER.info("[Discify] No lyrics found for \"" + title + "\" by " + artist);
                            success = true; // 404 is definitive, no need to retry
                        } else {
                            LOGGER.warn("[Discify] Failed to fetch lyrics: HTTP " + response.statusCode());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Discify] Failed to fetch lyrics (retry remaining: " + retries + "): " + e.getMessage());
                }

                if (!success) {
                    retries--;
                    if (retries >= 0) {
                        try { Thread.sleep(3000); } catch (Exception ignored) {}
                    }
                }
            }

            // Fallback to Search API if exact GET failed/timed out — also retried up to 5 times
            if (!success) {
                int fallbackRetries = 4;
                while (fallbackRetries >= 0 && !success) {
                    try {
                        synchronized (LyricsUtil.class) {
                            if (!key.equals(currentSongKey)) return;
                        }

                        String query = (artist != null ? artist + " " : "") + (title != null ? title : "");
                        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                        String url = LRCLIB_SEARCH_API + "?q=" + encodedQuery;

                        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                                .header("User-Agent", "Discify-MCMod/1.1 (github.com/SUHL2/Discify)")
                                .timeout(java.time.Duration.ofSeconds(5))
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                        synchronized (LyricsUtil.class) {
                            if (!key.equals(currentSongKey)) return;

                            if (response.statusCode() == 200) {
                                com.google.gson.JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                                if (array.size() > 0) {
                                    JsonObject first = array.get(0).getAsJsonObject();
                                    if (first.has("syncedLyrics") && !first.get("syncedLyrics").isJsonNull()) {
                                        String syncedLyrics = first.get("syncedLyrics").getAsString();
                                        cachedLyrics = parseLRC(syncedLyrics);
                                        LOGGER.info("[Discify] Loaded " + cachedLyrics.size() + " synced lyric lines (via search fallback) for \"" + title + "\"");
                                        success = true;
                                    } else if (first.has("plainLyrics") && !first.get("plainLyrics").isJsonNull()) {
                                        String plainLyrics = first.get("plainLyrics").getAsString();
                                        cachedLyrics = parsePlain(plainLyrics);
                                        LOGGER.info("[Discify] Loaded " + cachedLyrics.size() + " plain lyric lines (via search fallback) for \"" + title + "\"");
                                        success = true;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[Discify] Failed to fetch lyrics via search fallback (retry remaining: " + fallbackRetries + "): " + e.getMessage());
                    }

                    if (!success) {
                        fallbackRetries--;
                        if (fallbackRetries >= 0) {
                            try { Thread.sleep(3000); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            // Final fallback: lrcmux (aggregates Musixmatch, Kugou, YouTube Music, Genius)
            // Uses LRCLIB-compatible endpoint so same JSON shape
            if (!success) {
                int lrcmuxRetries = 4;
                while (lrcmuxRetries >= 0 && !success) {
                    try {
                        synchronized (LyricsUtil.class) {
                            if (!key.equals(currentSongKey)) return;
                        }

                        String encodedTitle = URLEncoder.encode(title != null ? title : "", StandardCharsets.UTF_8);
                        String encodedArtist = URLEncoder.encode(artist != null ? artist : "", StandardCharsets.UTF_8);
                        String url = LRCMUX_API + "?artist_name=" + encodedArtist + "&track_name=" + encodedTitle;

                        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                                .header("User-Agent", "Discify-MCMod/1.1 (github.com/SUHL2/Discify)")
                                .timeout(java.time.Duration.ofSeconds(5))
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                        synchronized (LyricsUtil.class) {
                            if (!key.equals(currentSongKey)) return;

                            if (response.statusCode() == 200) {
                                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                                if (json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull()) {
                                    String syncedLyrics = json.get("syncedLyrics").getAsString();
                                    cachedLyrics = parseLRC(syncedLyrics);
                                    LOGGER.info("[Discify] Loaded " + cachedLyrics.size() + " synced lyric lines (via lrcmux) for \"" + title + "\"");
                                    success = true;
                                } else if (json.has("plainLyrics") && !json.get("plainLyrics").isJsonNull()) {
                                    String plainLyrics = json.get("plainLyrics").getAsString();
                                    cachedLyrics = parsePlain(plainLyrics);
                                    LOGGER.info("[Discify] Loaded " + cachedLyrics.size() + " plain lyric lines (via lrcmux) for \"" + title + "\"");
                                    success = true;
                                }
                            } else if (response.statusCode() == 404) {
                                LOGGER.info("[Discify] lrcmux: no lyrics found for \"" + title + "\" by " + artist);
                                success = true; // definitive, stop retrying
                            } else {
                                LOGGER.warn("[Discify] lrcmux returned HTTP " + response.statusCode());
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[Discify] lrcmux fetch failed (retry remaining: " + lrcmuxRetries + "): " + e.getMessage());
                    }

                    if (!success) {
                        lrcmuxRetries--;
                        if (lrcmuxRetries >= 0) {
                            try { Thread.sleep(3000); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            synchronized (LyricsUtil.class) {
                if (key.equals(currentSongKey)) {
                    loading = false;
                }
            }
        });
        fetcher.setName("Discify Lyrics Fetcher");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    private static List<LyricLine> parseLRC(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = LRC_PATTERN.matcher(line);
            if (m.matches()) {
                int minutes = Integer.parseInt(m.group(1));
                double seconds = Double.parseDouble(m.group(2));
                int timeMs = (int) ((minutes * 60 + seconds) * 1000);
                String text = m.group(3).trim();
                if (text.isEmpty()) {
                    text = "♪";
                }
                lines.add(new LyricLine(timeMs, text));
            }
        }
        return lines;
    }

    private static List<LyricLine> parsePlain(String plain) {
        List<LyricLine> lines = new ArrayList<>();
        int idx = 0;
        for (String line : plain.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                lines.add(new LyricLine(idx * 5000, line));
                idx++;
            }
        }
        return lines;
    }

    public static String getCurrentLine(int progressMs) {
        String result = "";
        List<LyricLine> lyrics = cachedLyrics;
        for (LyricLine line : lyrics) {
            if (line.timeMs <= progressMs) {
                result = line.text;
            } else {
                break;
            }
        }
        return result;
    }

    public static String getNextLine(int progressMs) {
        List<LyricLine> lyrics = cachedLyrics;
        for (LyricLine line : lyrics) {
            if (line.timeMs > progressMs) {
                return line.text;
            }
        }
        return "";
    }

    public static boolean hasLyrics() {
        return !cachedLyrics.isEmpty();
    }

    public static boolean isLoading() {
        return loading;
    }

    public static List<LyricLine> getCachedLyrics() {
        return cachedLyrics;
    }

    public static double getSmoothIndex(int progressMs) {
        List<LyricLine> lyrics = cachedLyrics;
        if (lyrics.isEmpty()) return -1;

        int activeIndex = -1;
        for (int i = 0; i < lyrics.size(); i++) {
            if (lyrics.get(i).timeMs <= progressMs) {
                activeIndex = i;
            } else {
                break;
            }
        }

        if (activeIndex == -1) {
            return -1.0;
        }

        LyricLine activeLine = lyrics.get(activeIndex);
        long duration = 300; // transition duration in ms
        if (progressMs - activeLine.timeMs < duration) {
            double t = (double) (progressMs - activeLine.timeMs) / duration;
            double ease = t * t * (3 - 2 * t);
            return (activeIndex - 1) + ease;
        }

        return activeIndex;
    }

    public static class LyricLine {
        public final int timeMs;
        public final String text;

        public LyricLine(int timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
}
