package one.suhl2.discify.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the currently playing media via the Windows System Media Transport
 * Controls (SMTC) API — the same source as the Windows media popup / volume overlay.
 */
public class SMTCUtil {

    private static final Logger LOGGER = LogManager.getLogger("Discify");

    private static volatile boolean isPlaying = false;
    private static volatile Path scriptPath = null;

    private static final String PS_SCRIPT = String.join("\n",
        "$ErrorActionPreference = 'SilentlyContinue'",
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
        "try {",
        "  Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        "  $asTaskGenericOp = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {",
        "      $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'",
        "  })[0]",
        "  function Await-Op {",
        "      param($op, $ResultType)",
        "      $concrete = $asTaskGenericOp.MakeGenericMethod($ResultType)",
        "      $task = $concrete.Invoke($null, @($op))",
        "      $task.Wait(-1) | Out-Null",
        "      return $task.Result",
        "  }",
        "  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]",
        "  $opMgr = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()",
        "  $mgr = Await-Op $opMgr ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
        "  if ($null -eq $mgr) { Write-Output '{}'; exit 0 }",
        "  $s = $mgr.GetCurrentSession()",
        "  if ($null -eq $s) { Write-Output '{}'; exit 0 }",
        "  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]",
        "  $opMedia = $s.TryGetMediaPropertiesAsync()",
        "  $mp = Await-Op $opMedia ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
        "  $pi = $s.GetPlaybackInfo()",
        "  $tl = $s.GetTimelineProperties()",
        "  $playing = ($pi.PlaybackStatus.ToString() -eq 'Playing')",
        "  $thumbPath = ''",
        "  try {",
        "    if ($null -ne $mp.Thumbnail) {",
        "      $hash = [Math]::Abs(($mp.Title + $mp.Artist).GetHashCode())",
        "      $thumbPath = [IO.Path]::Combine([IO.Path]::GetTempPath(), \"discify_thumb_$hash.jpg\")",
        "      if (-not (Test-Path $thumbPath)) {",
        "        [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]",
        "        $opStream = $mp.Thumbnail.OpenReadAsync()",
        "        $stream = Await-Op $opStream ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])",
        "        if ($null -ne $stream) {",
        "          $asStreamMethod = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStream', [Type[]]@([Windows.Storage.Streams.IRandomAccessStream]))",
        "          $netStream = $asStreamMethod.Invoke($null, @($stream))",
        "          if ($null -ne $netStream) {",
        "            $fileStream = [System.IO.File]::Create($thumbPath)",
        "            $netStream.CopyTo($fileStream)",
        "            $fileStream.Dispose()",
        "            $netStream.Dispose()",
        "          }",
        "        }",
        "      }",
        "    }",
        "  } catch {}",
        "  $out = [ordered]@{",
        "    t   = [string]$mp.Title",
        "    a   = [string]$mp.Artist",
        "    p   = [bool]$playing",
        "    ms  = [long]$tl.Position.TotalMilliseconds",
        "    dur = [long]$tl.EndTime.TotalMilliseconds",
        "    th  = $thumbPath",
        "  }",
        "  Write-Output ($out | ConvertTo-Json -Compress)",
        "} catch {",
        "  Write-Output '{}'",
        "}"
    );

    public static String[] getMediaInfo() {
        String[] results = new String[8];

        if (!isWindows()) return results;

        try {
            ensureScriptFile();

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass",
                    "-File", scriptPath.toString());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            String raw = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor();

            if (raw.isEmpty()) {
                LOGGER.info("[Discify] SMTC script returned empty output");
                return results;
            }
            if (raw.equals("{}")) {
                LOGGER.info("[Discify] SMTC: no active media session");
                return results;
            }

            String jsonLine = raw.lines()
                    .filter(l -> l.startsWith("{"))
                    .findFirst()
                    .orElse(null);
            if (jsonLine == null) {
                LOGGER.warn("[Discify] SMTC unexpected output: " + raw.substring(0, Math.min(raw.length(), 200)));
                return results;
            }

            JsonObject json = new JsonParser().parse(jsonLine).getAsJsonObject();

            String title  = getStr(json, "t");
            String artist = getStr(json, "a");

            if (title == null || title.isEmpty()) {
                LOGGER.info("[Discify] SMTC returned empty title");
                return results;
            }

            results[0] = title;
            results[1] = artist != null ? artist : "";
            results[2] = String.valueOf(json.has("ms")  ? json.get("ms").getAsLong()  : 0L);
            results[3] = String.valueOf(json.has("dur") ? json.get("dur").getAsLong() : 0L);

            String thumbPath = getStr(json, "th");
            if (thumbPath != null && !thumbPath.isEmpty()) {
                File thumbFile = new File(thumbPath);
                if (thumbFile.exists() && thumbFile.length() > 0) {
                    results[4] = thumbFile.toURI().toString();
                }
            }

            isPlaying = json.has("p") && json.get("p").getAsBoolean();

            LOGGER.info("[Discify] SMTC: \"" + title + "\" by \"" + results[1]
                    + "\" | playing=" + isPlaying
                    + " | pos=" + results[2] + "ms / " + results[3] + "ms");

        } catch (Exception e) {
            LOGGER.error("[Discify] SMTCUtil.getMediaInfo failed: " + e.getMessage());
        }

        return results;
    }

    public static boolean isPlaying() {
        return isPlaying;
    }

    private static void ensureScriptFile() throws Exception {
        if (scriptPath != null && Files.exists(scriptPath)) return;
        scriptPath = Files.createTempFile("discify_smtc_", ".ps1");
        Files.writeString(scriptPath, PS_SCRIPT, StandardCharsets.UTF_8);
        scriptPath.toFile().deleteOnExit();
        LOGGER.info("[Discify] SMTC script written to: " + scriptPath);
    }

    private static String getStr(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return null;
        String v = json.get(key).getAsString();
        return v.isEmpty() ? null : v;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
