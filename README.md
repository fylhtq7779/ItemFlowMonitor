<div align="center">

<img src="src/main/resources/assets/itemflowmonitor/icon.png" width="128" height="128" alt="Item Flow Monitor">

# Item Flow Monitor

**Real-time item throughput monitoring for Minecraft containers**

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62B47A.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-DBD0B4.svg)](https://fabricmc.net/)

<br>

<img src="docs/images/demo.gif" width="640" alt="Item Flow Monitor Demo">

*Open any container, enable tracking, and see the item flow rate in real time.*

</div>

---

**Item Flow Monitor** adds an unobtrusive UI overlay to standard Minecraft containers that shows the rate of items flowing through them. Perfect for players who build farms and want to measure throughput without manual counting.

> This mod does **not** add any new blocks or items. It only adds an informational overlay to existing container screens.

## Features

- **Real-time flow rate** — see items/min or items/hour directly in the container UI
- **Time-to-full estimate** — hover over the rate to see when the container will be full
- **Three calculation modes** — Average (stable long-term), Actual (exact count per window), Predicted (real-time EMA estimate)
- **All standard containers** — chests, double chests, hoppers, furnaces, blast furnaces, smokers, barrels, dispensers, droppers, shulker boxes
- **Flexible item tracking** — track all items, auto-detect the first item, or manually select a specific one
- **Configurable via Mod Menu** — toggle tracking per container type
- **12 languages** — English, Russian, Chinese, Spanish, German, French, Japanese, Korean, Portuguese, Italian, Polish, Ukrainian
- **Lightweight** — observer-based architecture, zero overhead without active trackers

## Screenshots

<table>
<tr>
<td align="center"><img src="docs/images/overlay-chest.png" width="400" alt="Chest overlay"><br><b>Flow rate overlay on a chest</b></td>
<td align="center"><img src="docs/images/overlay-hopper.png" width="400" alt="Hopper overlay"><br><b>Monitoring a hopper</b></td>
</tr>
<tr>
<td align="center"><img src="docs/images/settings-closeup.png" width="400" alt="Settings panel"><br><b>Tracking settings panel</b></td>
<td align="center"><img src="docs/images/time-to-full.png" width="400" alt="Time to full tooltip"><br><b>Time-to-full estimate on hover</b></td>
</tr>
<tr>
<td align="center" colspan="2"><img src="docs/images/config-screen.png" width="400" alt="Mod Menu config"><br><b>Configuration screen via Mod Menu</b></td>
</tr>
</table>

## How to Use

1. Install the mod (requires [Fabric API](https://modrinth.com/mod/fabric-api))
2. Open any container and click the **IFM** button
3. Toggle tracking **ON**, choose your period and calculation mode
4. The flow rate overlay appears in the corner of the container screen
5. Hover over the rate to see estimated time until the container is full

## Compatibility

| | Status |
|---|---|
| **Lithium** | Fully compatible (observer-based, no hopper mixins) |
| **Sodium** | Fully compatible |
| **Iris Shaders** | Fully compatible |
| **Mod Menu** | Integrated (config screen) |
| **Client + Server** | Install on both sides for accurate tracking |

## Configuration

Access settings through **Mod Menu** → Item Flow Monitor.

Toggle which container types show the IFM button: Chests, Double Chests, Barrels, Ender Chests, Shulker Boxes, Hoppers, Dispensers, Droppers, Furnaces, Smokers, Blast Furnaces.

## Building from Source

```bash
git clone https://github.com/fylhtq7779/ItemFlowMonitor.git
cd ItemFlowMonitor
./gradlew build
```

The built jar will be in `build/libs/`.

## Contributing

Contributions are welcome! Feel free to:

- Report bugs via [GitHub Issues](https://github.com/fylhtq7779/ItemFlowMonitor/issues)
- Submit pull requests
- Help with [translations](src/main/resources/assets/itemflowmonitor/lang/)

## License

This project is licensed under the [MIT License](LICENSE).
