# Flippers

The MVP ships four flippers on one shared data layer and [calculation engine](calculation-engine.md), plus a [Forge Tracker](forge.md#forge-tracker) and an expected-vs-realised profit check.

**Build order inside the MVP:** data layer → Forge → Craft → NPC → Bazaar.

```mermaid
flowchart LR
    D["Data layer<br/>collectors · mapping · storage"] --> F[Forge Flipper] --> C[Craft Flipper] --> N[NPC Flipper] --> B[Bazaar Flipper]
    F -.-> T[Forge Tracker]
    F -.-> E[Expected vs realised]
```

| Feature | What it does | Personal context used | Feasibility | Status |
| --- | --- | --- | --- | --- |
| Forge Flipper | Ranks forge recipes by profit, using real forge speed and the time until the next refill | HOTM, collections, forge speed, budget, next session | Feasible; forge timing from API to verify | **Implemented** (M2) |
| Craft Flipper | Ranks craft recipes by ingredient cost versus output value | Collections, budget | Feasible | Implemented |
| NPC Flipper | Buy from NPC, sell on Bazaar/AH (or Bazaar → NPC) | Budget | Feasible; models the 640/day NPC shop limit | Implemented |
| Bazaar Flipper | Shows spread opportunities with volume and liquidity | Budget, tax rate | Feasible; info only, competes with scripts, lowest priority | Implemented |
| Forge Tracker | Time left on each forge slot without visiting the Forge | Slots, forge speed | Manual entry with calibration; API forge data to verify | **Implemented** (M2, manual entry) |
| Expected vs realised profit | Compares prices at forge start with prices at finish | Stored snapshots | Feasible with snapshot storage | **Implemented** (M2) |

## Common shape

Each flipper is a Spring service in `flipper.<type>` that:

1. loads candidates (recipes, NPC offers or Bazaar products),
2. filters by personal context (unlocked, affordable),
3. asks the engine for cost, value, fees and metrics,
4. attaches alerts and a guide,
5. returns a ranked list to `GET /api/v1/flips/{type}` and one opportunity with its cost sheet and guide to `GET /api/v1/flips/{type}/{id}`.

```mermaid
classDiagram
    class Flipper {
        <<interface>>
        +type() FlipType
        +list(FlipQuery) FlipList
        +detail(id, FlipQuery) Optional~OpportunityDetail~
    }
    class FlipList {
        +total
        +generatedAt
        +settings
        +items List~Opportunity~
    }
    class Opportunity {
        +id, name, outputId
        +cost, value, fees, netProfit, roi
        +durationSeconds, sessions, profitPerSession
        +sellVenue, liquidity, unlock
        +complete, alerts
    }
    class OpportunityDetail {
        +summary Opportunity
        +sheet CostSheet
        +unlock Unlock
        +guide Guide
        +names
    }
    class Guide {
        +steps List~String~
        +logic String
    }
    Flipper <|.. ForgeFlipper
    Flipper <|.. CraftFlipper
    Flipper <|.. NpcFlipper
    Flipper <|.. BazaarFlipper
    Flipper --> FlipList
    FlipList --> Opportunity
    Flipper --> OpportunityDetail
    OpportunityDetail --> Guide
```

`FlipQuery` carries budget, time to next session, sort, minimum volume, `includeLocked`, `includeIncomplete` and paging; unset values come from the player context. Money in an opportunity is per run (per slot for the forge). The Bazaar Flipper ranks products instead of recipes but returns the same shapes.

Shared pieces live in the `flipper` package: `RecipeBook` (NEU recipes indexed by type and output, rebuilt when NEU updates), `FlipContext` (one market view, player context, unlock cache and craft planner per request) and `FlipSupport` (filters, paging, alerts, names, guide steps).

**Missing prices are hidden by default.** An opportunity whose input or output has no price is incomplete; with thousands of craft recipes these would flood the list, so they only appear with `includeIncomplete=true` (UI: *Show missing prices*), ranked last.

## Per-flip guide and visualised recipe

Every flipper includes:

- a short **step-by-step list**, for example *buy ingredients → put in forge → collect → sell output*;
- a **mini write-up** of the logic behind it (why it is profitable, what can go wrong);
- the **recipe**, ingredient prices, totals and a cost/profit sheet for each opportunity.

The guide only describes what the player does by hand. The app never performs a step.

## Forge Flipper

```mermaid
flowchart LR
    A[Buy ingredients<br/>Bazaar / AH] --> B[Start forge<br/>in game]
    B --> C[Wait real duration]
    C --> D[Collect output]
    D --> E[Sell output<br/>Bazaar / AH]
```

**Status:** Implemented (`flipper.forge.ForgeFlipper`, UI tab *Forge Flipper*).

- All NEU forge recipes (124 on 2026-11-17) are evaluated on each request; the list answers in about 150 ms.
- Recipes gated by HOTM level or a collection tier are hidden when locked. Requirements the context cannot check are shown with an `UNLOCK_UNKNOWN` alert.
- Duration uses the player's real forge speed, not the base duration. See [Forge](forge.md).
- Ranking defaults to profit per session, so very short recipes are not inflated ([ADR 0007](decisions/0007-profit-per-session.md)).
- The guide lists each purchase (Bazaar buy order or insta-buy, AH BIN), recipe coins, where to start the recipe, when to collect and where to sell.
- Starting a job can be recorded from the detail panel to feed the Forge Tracker and the expected-vs-realised check.

First live run (2026-11-17, HOTM 7, 20% Quick Forge, 8 h sessions): Gleaming Crystal led the Bazaar-sold recipes at about 13.5M per session. Several AH-sold outputs (drills, Divan's Drill) ranked higher but with `LOW_VOLUME` alerts (and, before the venue columns, `AH_SOLD_OUTPUT`). Some results look surprising and are **to verify** in game, for example the Mithril Belt (NEU lists 3 Enchanted Mithril as its input; the AH lowest BIN was 490k).

## Craft Flipper

```mermaid
flowchart LR
    A[Buy leaf ingredients<br/>Bazaar / AH] --> B[Craft intermediates<br/>deepest first]
    B --> C[Craft the output]
    C --> D[Sell output<br/>Bazaar / AH / NPC]
```

**Status:** Implemented (`flipper.craft.CraftFlipper`, UI tab *Craft Flipper*).

- All NEU crafting recipes with inputs are evaluated on each request (1,415 outputs with prices on 2026-11-17); the list answers in about 0.3–0.6 s.
- One opportunity per output item: when an item has several recipes (Enchanted Diamond from diamonds or from diamond blocks), the most profitable complete one is shown.
- Recipes gated by a collection tier (`Requires: Diamond IV`) or HOTM level are hidden when locked, like forge recipes.
- **Intermediates:** each input is bought or crafted from its own ingredients, whichever is cheaper, down to 3 levels (see [Calculation engine](calculation-engine.md#intermediates-buy-or-craft)). The Forge Flipper and Forge Tracker use the same planner, so forge inputs such as Enchanted Mithril are crafted when that is cheaper.
- Crafting is instant: duration is 0, there is no profit per hour, and ranking is by profit per craft or ROI. The real limit is how much the market absorbs, so the guide states the daily volume.
- The guide lists purchases summed per item, each intermediate craft with what it saves over buying, the final craft and where to sell. The detail panel shows the ingredient tree.

First live run (2026-11-17): the Bazaar-sold leaders were Hotspot Water Orb (~47M per craft) and Ultimate Bobbin' Time (~30M). Most high-profit rows sold on the AH, often with `LOW_VOLUME`. Thin AH markets were a weak point: one joke listing or one upgraded copy set the value (a 100M Rabbit Helmet that never sold, a 44M 5-star Yeti Sword).

**After the first test (2026-11-18):** AH values come from clean copies only, `FEW_SALES` flags values from fewer than 3 clean sales (hidden by default), and Coflnet backfill supplies a week of sales. With those, the joke rows drop out of the default list; Annihilation Cloak (clean sales about 196M) leads with `UNLOCK_UNKNOWN` for Blaze Slayer 7, and Ultimate Carrot Candy keeps its row but with two `WIDE_SPREAD` alerts: buying Superb Carrot Candy instantly instead of by order would cost more than the whole profit.

## NPC Flipper

```mermaid
flowchart LR
    subgraph shop ["shop route"]
        S1[Buy from NPC shop<br/>coins, sometimes items] --> S2[Sell on Bazaar / AH]
    end
    subgraph sell ["sell route"]
        B1[Buy on the Bazaar] --> B2[Sell to any NPC]
    end
    S2 & B2 --> R["rank by profit per day<br/>= profit per run × runs per day"]
```

**Status:** Implemented (`flipper.npc.NpcFlipper`, UI tab *NPC Flipper*).

- **Shop route** (`shop:<NPC>:<item>`): every NEU `npc_shop` recipe (1,095 on 2026-11-17). Coin costs are the recipe coins; item costs are bought or crafted like recipe inputs. Shops paid in special currencies (Bits, Copper, Motes, Pelts, North Stars…) have no market price, so they are incomplete and hidden by default.
- **Sell route** (`sell:<item>`): every Bazaar product with an NPC sell price (Hypixel items resource). Bought on the Bazaar only, sold to an NPC without fees.
- **Runs per day:**
  - shop: `min(npc.daily-limit, npc.market-share × output daily volume) ÷ items per purchase`; the market share part is skipped when the output sells to an NPC;
  - sell: `npc.market-share × daily volume` of the item (NPCs buy any amount).
- Ranking defaults to profit per day; profit per run and ROI are available. There is no duration.
- The guide names the NPC and its island (from NEU, for example *Zog (Hub)*) and how often to repeat.
- All 1,328 priced routes answer in about 0.2–0.3 s.

**To verify:** that the 640 limit is per item across all NPCs, that selling to NPCs is unlimited, and whether 5% of daily volume is a sensible default share.

First live run (2026-11-17): Jungle Key from Odawa (~76M a day at the 640 limit) and Bazaar → NPC routes for Flawless gemstones and enchanted crops led. Several top rows were momentary Bazaar dumps (Enchanted Potato's top buy order fell from 235 to 2–3); they carry a `RECENT_CHANGE` alert. Pet outputs (Golden Dragon) are overvalued because AH sales mix pet levels.

## Bazaar Flipper

```mermaid
flowchart LR
    P["Bazaar poll<br/>(every 60 s)"] --> BK["BazaarBook<br/>best order of each side,<br/>in memory"]
    P --> Q[("bazaar_snapshot<br/>quick_status")]
    BK -- fresh --> S["spread<br/>buy at best buy order + 0.1<br/>sell at best sell offer − 0.1"]
    Q -- "no live book yet" --> S
    S --> T["− Bazaar tax on the sale<br/>= profit per unit"]
    Q --> V["units per day =<br/>market-share × slower side's<br/>filled volume"]
    T & V --> R["rank by profit per day"]
```

**Status:** Implemented (`flipper.bazaar.BazaarFlipper`, UI tab *Bazaar Flipper*). **Information only:** it competes with automated scripts, warns that spreads close quickly, and HyFlip never places or relists orders.

- **Candidates:** every Bazaar product with orders on both sides (about 1,740 on 2026-11-18). The ID is the item ID.
- **Prices:** always order to order, whatever `trading.price-source` says. A buy order goes one step (0.1) above the best buy order, a sell offer one step under the best sell offer; the Bazaar tax is taken on the sale.
- **Live book:** `quick_status` prices are averages over the top of the book and can sit far from the best single order (Enchanted Wheat on 2026-11-18: `buyPrice` 973 while the best sell offer was 406.7). The Bazaar collector therefore keeps the best price level of each side from every poll in memory (`market.BazaarBook`); it is not stored. Until the first poll after a start, the flipper falls back to `quick_status` and says so in the guide.
- **Units per day:** buy orders fill when others insta-sell (`sell_moving_week`), sell offers when others insta-buy (`buy_moving_week`). The slower side limits the flip; `bazaar.market-share` of it is the daily estimate.
- **Alerts:** the shared alerts (`RECENT_CHANGE`, `STALE_PRICE`, `LOW_VOLUME`) compare `quick_status` with its own history, so a live book price is not mistaken for a sudden move. `THIN_BOOK` is added when a side has fewer than `bazaar.thin-book` orders, or when the best order stands more than 25% away from the next price level (a lone order that sets the spread).
- **Guide:** buy order size and total, the sell offer price, and a reminder to check the book first.
- The full list answers in about 0.25 s.

**To verify:** whether 5% of the slower side is a sensible share when scripts compete for the same spread, and whether fills really follow the moving-week volumes.

First live run (2026-11-18): high-value products with steady volume led (Wither Catalyst ~16% per unit, ~115M a day at 5%; Fuming Potato Book; Jungle Key). Many high-ROI rows (Enchanted Honeycomb 250–530%, Glacial Fragment, Kuudra Teeth) carry `RECENT_CHANGE` or `THIN_BOOK`: the book moved during the last minutes or a single order sets the spread. Before the live book was used, `quick_status` averages inflated spreads (Enchanted Wheat showed 186% ROI instead of about 15%).

## Expected vs realised profit

**Status:** Implemented (Forge Tracker tab, `GET /api/v1/forge/jobs`).

When a forge job is recorded, the expected cost and net output value are stored in `forge_job`. Once its end has passed, the realised value is computed from the last prices at or before the end. The difference shows how much prices moved during the job and feeds trust in future rankings.
