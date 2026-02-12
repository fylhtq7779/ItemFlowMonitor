# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] - 2025-02-12

### Added

- Real-time item flow rate overlay for containers (items/min, items/hour)
- Time-to-full estimate tooltip on hover
- Three calculation modes: Average, Actual, Predicted (EMA smoothing)
- Support for all standard containers: chests, double chests, hoppers, furnaces, blast furnaces, smokers, barrels, dispensers, droppers, shulker boxes
- Three item tracking modes: All Items, First Item (auto-detect), Pick Item (manual selection)
- Settings panel with toggle, period, rate mode, tracking mode, item selector, and reset
- Tooltips for all panel elements with 500ms delay
- Configuration screen via Mod Menu (toggle per container type)
- Observer-based tracking architecture (Lithium compatible)
- Network optimization: delta-check with 1-second fallback
- Ghost tracker protection: auto-pause after 5 minutes without viewers
- Tracker limit: 32 per player
- Automatic cleanup on container destruction (including explosions)
- Persistent tracker settings (saved to world data)
- Double chest normalization (canonical position)
- Localization: 12 languages (en, ru, zh_cn, zh_tw, de, fr, es, pt_br, ja, ko, uk, pl)
