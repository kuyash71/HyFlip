# HyFlip Documentation

HyFlip (Hypixel SkyBlock Flipping Toolkit) is a local-first, open-source desktop app that finds and explains flipping opportunities, personalised to the player's own account. It reads and shows; it never acts in the game.

## Start here

| Document | What it covers |
| --- | --- |
| [Overview](overview.md) | Why the project exists, design principles, non-goals |
| [Architecture](architecture.md) | Components, runtime model, server modules, security |
| [Development](development.md) | Prerequisites, running in dev, building, testing, repository layout |

## Reference

| Document | What it covers |
| --- | --- |
| [Data sources](data-sources.md) | Hypixel, NEU, Mojang and Coflnet endpoints, polling, rate limits, ID mapping |
| [Database](database.md) | Schema, retention, downsampling, import/export, storage estimates |
| [Configuration](configuration.md) | Every `config.yaml` field and where the file lives |
| [REST API](api.md) | Conventions and endpoints between the UI and the local server |
| [Calculation engine](calculation-engine.md) | Costs, taxes, liquidity, profit metrics, ranking |
| [Flippers](flippers.md) | Forge, Craft, NPC and Bazaar flippers; per-flip guides |
| [Forge](forge.md) | Forge speed model, calibration wizard, Forge Tracker |
| [Alerts](alerts.md) | Risk alerts and their triggers |
| [Roadmap](roadmap.md) | MVP build order, post-MVP work, open questions, risks |
| [Decisions](decisions/README.md) | Architecture decision records (ADRs) |

## Status legend

Documents mark items with a status so readers know what exists:

- **Implemented**: present in the code.
- **Planned**: agreed design, not built yet.
- **To verify**: depends on an external fact (API field, game value) that must be tested.
- **Open**: decision not made yet.
