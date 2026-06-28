package one.suhl2.discify.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Windows Core Audio per-application volume control via JNA.
 *
 * Uses the same COM interfaces that Windows Volume Mixer uses internally:
 *   IMMDeviceEnumerator -> IMMDevice -> IAudioSessionManager2
 *     -> IAudioSessionEnumerator -> IAudioSessionControl2 -> ISimpleAudioVolume
 *
 * Finds the Spotify audio session by matching the owning PID against all
 * running "Spotify.exe" processes, then calls SetMasterVolume / GetMasterVolume
 * on the matching ISimpleAudioVolume interface.
 *
 * Gracefully no-ops on non-Windows platforms.
 */
public class WindowsVolumeUtil {

    private static final Logger LOGGER = LogManager.getLogger("Discify");

    private static final String CLSID_MMDeviceEnumerator  = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    private static final String IID_IMMDeviceEnumerator   = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";
    private static final String IID_IAudioSessionManager2 = "{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}";
    private static final String IID_ISimpleAudioVolume    = "{87CE5498-68D6-44E5-9215-6DA47EF883D8}";
    private static final String IID_IAudioSessionControl2 = "{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}";

    private static final int DATA_FLOW_RENDER = 0;
    private static final int ROLE_MULTIMEDIA  = 1;

    private static int comCall(Pointer pUnknown, int vTableIndex, Object... args) {
        Pointer vtable = pUnknown.getPointer(0);
        com.sun.jna.Function fn = com.sun.jna.Function.getFunction(
                vtable.getPointer((long) vTableIndex * Native.POINTER_SIZE),
                StdCallLibrary.STDCALL_CONVENTION);
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = pUnknown;
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        Object result = fn.invoke(Integer.class, fullArgs);
        return result instanceof Integer ? (Integer) result : 0;
    }

    private static Pointer queryInterface(Pointer pUnknown, String iid) {
        PointerByReference ppv  = new PointerByReference();
        Guid.GUID guid = new Guid.GUID(iid);
        int hr = comCall(pUnknown, 0, guid, ppv);
        if (hr != 0) return null;
        return ppv.getValue();
    }

    private static void release(Pointer pUnknown) {
        if (pUnknown != null) comCall(pUnknown, 2);
    }

    public static float getSpotifyVolume() {
        return withSpotifyVolume(null, false);
    }

    public static void setSpotifyVolume(float volume) {
        withSpotifyVolume(volume, true);
    }

    private static float withSpotifyVolume(Float volume, boolean set) {
        if (!isWindows()) {
            LOGGER.warn("[Discify] Per-app volume control is only supported on Windows.");
            return -1f;
        }

        int[] spotifyPids = getSpotifyPids();
        if (spotifyPids.length == 0) {
            LOGGER.warn("[Discify] No Spotify.exe process found.");
            return -1f;
        }

        Ole32.INSTANCE.CoInitializeEx(null, 0x2);

        Pointer pEnumerator  = null;
        Pointer pDevice      = null;
        Pointer pManager     = null;
        Pointer pSessionEnum = null;

        try {
            PointerByReference ppEnum = new PointerByReference();
            Guid.GUID clsid = new Guid.GUID(CLSID_MMDeviceEnumerator);
            Guid.GUID riid  = new Guid.GUID(IID_IMMDeviceEnumerator);
            WinNT.HRESULT coHr = Ole32.INSTANCE.CoCreateInstance(clsid, null, 1, riid, ppEnum);
            int hr = coHr.intValue();
            if (hr != 0) {
                LOGGER.error("[Discify] CoCreateInstance failed: 0x" + Integer.toHexString(hr));
                return -1f;
            }
            pEnumerator = ppEnum.getValue();

            PointerByReference ppDevice = new PointerByReference();
            hr = comCall(pEnumerator, 4, DATA_FLOW_RENDER, ROLE_MULTIMEDIA, ppDevice);
            if (hr != 0) {
                LOGGER.error("[Discify] GetDefaultAudioEndpoint failed: 0x" + Integer.toHexString(hr));
                return -1f;
            }
            pDevice = ppDevice.getValue();

            PointerByReference ppManager = new PointerByReference();
            Guid.GUID iidASM2 = new Guid.GUID(IID_IAudioSessionManager2);
            hr = comCall(pDevice, 3, iidASM2, 23, null, ppManager);
            if (hr != 0) {
                LOGGER.error("[Discify] IMMDevice::Activate(IAudioSessionManager2) failed: 0x" + Integer.toHexString(hr));
                return -1f;
            }
            pManager = ppManager.getValue();

            PointerByReference ppSessionEnum = new PointerByReference();
            hr = comCall(pManager, 5, ppSessionEnum);
            if (hr != 0) {
                LOGGER.error("[Discify] GetSessionEnumerator failed: 0x" + Integer.toHexString(hr));
                return -1f;
            }
            pSessionEnum = ppSessionEnum.getValue();

            IntByReference pCount = new IntByReference();
            hr = comCall(pSessionEnum, 3, pCount);
            if (hr != 0) {
                LOGGER.error("[Discify] GetCount failed: 0x" + Integer.toHexString(hr));
                return -1f;
            }
            int count = pCount.getValue();

            for (int i = 0; i < count; i++) {
                PointerByReference ppCtrl = new PointerByReference();
                hr = comCall(pSessionEnum, 4, i, ppCtrl);
                if (hr != 0) continue;
                Pointer pCtrl = ppCtrl.getValue();

                Pointer pCtrl2 = queryInterface(pCtrl, IID_IAudioSessionControl2);
                if (pCtrl2 == null) {
                    release(pCtrl);
                    continue;
                }

                IntByReference pPid = new IntByReference();
                int pidHr = comCall(pCtrl2, 14, pPid);
                int sessionPid = (pidHr == 0) ? pPid.getValue() : -1;
                release(pCtrl2);

                boolean isSpotify = false;
                for (int spPid : spotifyPids) {
                    if (spPid == sessionPid) {
                        isSpotify = true;
                        break;
                    }
                }

                if (!isSpotify) {
                    release(pCtrl);
                    continue;
                }

                Pointer pSimpleVol = queryInterface(pCtrl, IID_ISimpleAudioVolume);
                release(pCtrl);

                if (pSimpleVol == null) continue;

                try {
                    if (set) {
                        comCall(pSimpleVol, 3, volume.floatValue(), Pointer.NULL);
                        LOGGER.info("[Discify] Set Spotify volume to " + Math.round(volume * 100) + "%");
                        return volume;
                    } else {
                        FloatByReference pVol = new FloatByReference();
                        comCall(pSimpleVol, 4, pVol);
                        float result = pVol.getValue();
                        LOGGER.info("[Discify] Got Spotify volume: " + Math.round(result * 100) + "%");
                        return result;
                    }
                } finally {
                    release(pSimpleVol);
                }
            }

            LOGGER.warn("[Discify] No audio session matched a Spotify PID.");
            return -1f;

        } catch (Exception e) {
            LOGGER.error("[Discify] WindowsVolumeUtil error: " + e.getMessage());
            return -1f;
        } finally {
            release(pSessionEnum);
            release(pManager);
            release(pDevice);
            release(pEnumerator);
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static int[] getSpotifyPids() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tasklist", "/FI", "IMAGENAME eq Spotify.exe", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            java.util.List<Integer> pids = new java.util.ArrayList<>();
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("INFO:")) continue;
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    try {
                        int pid = Integer.parseInt(parts[1].replaceAll("\"", "").trim());
                        pids.add(pid);
                    } catch (NumberFormatException ignored) {}
                }
            }
            return pids.stream().mapToInt(Integer::intValue).toArray();
        } catch (Exception e) {
            LOGGER.error("[Discify] Failed to enumerate Spotify PIDs: " + e.getMessage());
            return new int[0];
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
