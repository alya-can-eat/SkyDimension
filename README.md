# SkyDimension
Server Plugin to access Sky dimension for Minecraft b1.7.3 Poseidon (CB1060 Fork; only tested on Poseidon)

Requires no client-side mods. 

![Sky Dimension via this Plugin](https://raw.githubusercontent.com/alya-can-eat/SkyDimension/refs/heads/main/.img/Screenshot_2026-10-17%2011-12-58.png)

---

> [!WARNING]
> This plugin was vibecoded but seems to work good and the code looks fine.

---

Unlocks the hidden **Sky dimension** of Minecraft Beta 1.7.3 for Project Poseidon servers.

The Sky dimension is fully present in the Beta 1.7.3 code: floating islands, a frozen sky, its own world generator. It was never made reachable. This plugin creates the world and adds a vanilla-feeling way to get there: a glowstone portal, inspired by the Aether mod, and Notch's idea of a dream world.

> In a live webcast on feber.se, Notch said the Sky dimension would "probably be a dream world", and that when you go to bed there is a certain chance you get dragged into the dream world instead.

No client mod is needed. The vanilla Beta 1.7.3 client still knows the Sky dimension and renders it correctly.

## Features

- **Sky dimension world** generated with the original Beta 1.7.3 sky generator, using the same seed as the overworld (like the Nether).
- **Glowstone portal**: build a frame like a Nether portal out of glowstone (4×5, corners optional) and pour a water bucket inside. The frame fills with portal blocks. Stand inside for 4 seconds to travel.
- **1:1 coordinates** between overworld and Sky.
- **Return portals**: if there is no glowstone portal near your destination, one is built for you, so you can always get back.
- **Dream world**: when you go to bed in the overworld, there is a configurable chance (default 10 %) to be pulled into the Sky just as you fall asleep. Your bed spawn is still set.
- **Falling out of the Sky** drops you onto the overworld surface below, without fall damage.
- **Safe arrival**: you always land on solid ground. Over open void, a small platform of decaying leaves is placed, which disappears on its own.
- **Silent and vanilla-feeling**: no chat messages during gameplay. Only commands reply.
- **Portals collapse** when the frame is broken, just like Nether portals.

![Leaves platform](https://github.com/alya-can-eat/SkyDimension/blob/main/.img/Screenshot_2026-10-17%2011-29-04.png?raw=true)

## Requirements

- Project Poseidon (Beta 1.7.3 Server - Fork of CB1060)
  - https://github.com/retromcorg/Project-Poseidon
- Java 8

Only tested with Project Poseidon. It may work on CraftBukkit 1060, but that is untested.

## Installation

1. Put `SkyDimension*.jar` into the `plugins/` folder. Get from here: https://github.com/alya-can-eat/SkyDimension/releases
2. Start the server. The Sky world `world_sky` and `plugins/SkyDimension/config.yml` are created on the first start.

## Building and entering a portal

1. Build the frame out of glowstone in the same shape as a nether portal.
2. Pour a water bucket into the opening.
3. Stand in the portal for 4 seconds.

![Sky Portal](https://raw.githubusercontent.com/alya-can-eat/SkyDimension/refs/heads/main/.img/Screenshot_2026-10-17%2011-11-46.png)

## Commands

| Command | Description | Permission |
|---|---|---|
| `/sky` | Travel to the Sky, or back if you are already there | `skydimension.command` (or everyone, if enabled in the config) |
| `/sky <player>` | Send another player | `skydimension.others` |
| `/sky toggle` | Turn `/sky` on or off for normal players | `skydimension.toggle` |

By default, normal players can only reach the Sky through portals or dreams.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `skydimension.command` | op | Use `/sky` even when it is disabled for players |
| `skydimension.others` | op | Send other players with `/sky <player>` |
| `skydimension.toggle` | op | Switch `/sky` on or off for players |
| `skydimension.portal.create` | everyone | Light a glowstone portal with water |
| `skydimension.portal.use` | everyone | Travel through a glowstone portal |

## Configuration

`plugins/SkyDimension/config.yml`

| Key | Default | Description |
|---|---|---|
| `world-name` | `world_sky` | Folder name of the Sky world |
| `players-can-use-command` | `false` | Allow `/sky` for normal players |
| `fall-to-overworld` | `true` | Falling out of the Sky lands you in the overworld |
| `create-return-portal` | `true` | Build a portal at the destination if none exists |
| `safe-spot-search-radius` | `48` | Radius in blocks to search for solid ground on arrival |
| `portal-search-radius` | `16` | Minimum radius to search for an existing portal (always at least the ground search radius + 4) |
| `dream-chance` | `10` | Chance in % to be pulled into the dream world when going to bed. `0` disables it |
| `sky-snow` | `false` | Renew snow and freeze water in the Sky. Vanilla does not regenerate snow. No rain or snow is visible there |

## Notes

- Time in the Sky is frozen at noon, so you cannot sleep there. This is vanilla behaviour.
- Glowstone portals only connect the overworld and the Sky.
- Vanilla obsidian Nether portals are not affected.
- The return point of `/sky` is kept in memory only. After a restart, `/sky` from the Sky takes you to the overworld spawn.

## Building from source

Compile against the Project Poseidon server jar (Java 8 target):

```bash
javac --release 8 -cp poseidon.jar -d build src/ch/alya/skydimension/*.java
cp plugin.yml build/
jar cf SkyDimension.jar -C build .
```
