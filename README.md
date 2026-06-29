# Discify

Discify is a modern Minecraft mod that brings your Spotify experience directly into the game. As a fork of the original [Blockify](https://github.com/BuffMage/Blockify), it overlays essential playback information—such as the track title, artist, album art, progress bar, and volume—directly onto your Minecraft HUD.

## Quick Links
[![Crowdin](https://is.gd/TfsUVl)](https://www.crowdin.com/project/blockify)
[![CurseForge](https://is.gd/BeBNjV)](https://www.curseforge.com/minecraft/mc-mods/discify-spotify-mod)
[![Modrinth](https://is.gd/h1Lgw5)](https://modrinth.com/mod/discify-2)
[![License](https://img.shields.io/github/license/clownless/blockify?style=flat-square)](https://github.com/clownless/Blockify/blob/main/LICENSE)
[![ModLoader](https://img.shields.io/badge/modloader-Fabric%2C%20Quilt-1976d2?style=flat-square)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/minecraft-26.1.2-1976d2?style=flat-square)](https://www.minecraft.net/)
[![Java Version](https://img.shields.io/badge/java-25%20(or%20above)-1976d2?style=flat-square)](https://adoptium.net/releases.html)
[![Discord](https://img.shields.io/discord/837540892411691008?label=discord&style=flat-square)](https://discord.gg/bSgZxY3rQm)

## Requirements
- Minecraft **26.1.2** with **Fabric Loader** 0.19.2+ and **Fabric API**
- Java **25** or newer
- [MidnightLib](https://modrinth.com/mod/midnightlib) 1.9.3+ (for the in-game config screen)
- A **Spotify Premium** account (the Web API endpoints used for playback control are Premium-only)

## Usage
Simply play a song on Spotify and load into your Minecraft world. Authentication is only required if you want to save tracks to your library (press 'L').

### Key Bindings (Configurable)
| Action | Key |
| --- | --- |
| Play/Pause (or Authorize on first run) | `Num 5` |
| Previous Song | `Num 4` |
| Next Song | `Num 6` |
| Save current track | `L` |
| Force Update | `Num 8` |
| Hide HUD | `Num 9` |
| Increase Volume | `Num +` |
| Decrease Volume | `Num -` |
| Toggle Music | `Num 1` |

## Commands
- `/sharetrack` — Sends a link to the currently playing track into chat (visible only to you).

## Configuration
Access settings in-game via **Mod Menu → Discify**. Adjust HUD scale, positioning, album art visibility, custom colors, transparency, and more.

## Building
```bash
./gradlew build          # build the mod JAR
./gradlew runClient      # launch a dev Minecraft client
```

## Acknowledgements & Contributing
Discify is a fork of the original [Blockify](https://github.com/BuffMage/Blockify). We highly encourage users to support the original creators!

# Everything past this line only benefits the Blockify developers.
Contributions are **greatly appreciated**.

1. Fork the project.
2. Create a feature branch.
3. Commit your changes.
4. Push to the branch.
5. Open a Pull Request.

### Localisation
Translations are managed through [Crowdin](https://www.crowdin.com/project/blockify). Please do not open PRs that hardcode translated strings.

---
*UI/HUD aesthetics are based on the original Blockify design.*
