Real-time item throughput monitoring for Minecraft containers.

**Item Flow Monitor** adds an unobtrusive UI overlay to standard containers (chests, hoppers, furnaces, and more) that shows the rate of items flowing through them — in items/min or items/hour. Perfect for players who build farms and want to measure performance without manual counting.

> This mod does **not** add any new blocks or items. It only adds an informational overlay to existing container screens.

![Item Flow Monitor Demo](url_demo_gif)

## Features

- **Real-time flow rate** — see items/min or items/hour directly in the container UI
- **Time-to-full estimate** — hover over the rate to see when the container will be full
- **Three calculation modes** — Average (stable long-term), Actual (exact count per window), Predicted (real-time EMA estimate)
- **All standard containers** — chests, double chests, hoppers, furnaces, blast furnaces, smokers, barrels, dispensers, droppers, shulker boxes
- **Flexible item tracking** — track all items, auto-detect the first item, or manually select a specific one
- **Configurable via Mod Menu** — toggle tracking per container type
- **12 languages** — English, Русский, 中文, Español, Deutsch, Français, 日本語, 한국어, Português, Italiano, Polski, Українська
- **Lightweight** — observer-based architecture, zero overhead without active trackers

## How to Use

1. Install the mod (requires [Fabric API](https://modrinth.com/mod/fabric-api))
2. Open any container and click the **IFM** button
3. Toggle tracking **ON**, choose your period and calculation mode
4. The flow rate overlay appears in the corner of the container screen
5. Hover over the rate to see estimated time until the container is full

![Settings panel](url_settings_screenshot)

## Compatibility

- **Client + Server** — install on both sides for accurate real-time data
- **Lithium compatible** — observer-based tracking, no hopper mixin conflicts
- **Works with** Mod Menu, Sodium, Iris, Fabric API
- Tested on Minecraft 1.21.11

## FAQ

**Q: Does it work on vanilla servers?**
A: The mod requires server-side installation for tracking. Client-only mode is not currently supported.

**Q: Does it affect performance?**
A: No. The mod only tracks containers with active trackers. Zero overhead without trackers.

**Q: Can I hide the overlay for specific container types?**
A: Yes — configure through Mod Menu → Item Flow Monitor.
