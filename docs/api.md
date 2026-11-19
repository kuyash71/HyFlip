# REST API

The local server serves the desktop UI over HTTP on `127.0.0.1`. It is not a public API: nothing outside the user's machine may call it, and it must never relay Hypixel data to third parties.

## Conventions

| Topic | Rule |
| --- | --- |
| Base path | `/api/v1` |
| Format | JSON, UTF-8, `camelCase` fields |
| Auth | Header `X-HyFlip-Token: <per-launch token>` on every request; `401` without it |
| Time | Epoch milliseconds (`...At` fields) and ISO-8601 durations where noted |
| Money | Coins as numbers; the UI formats them |
| Errors | RFC 9457 problem details: `{ type, title, status, detail, instance }`; `detail` is the message the UI shows |
| Lists | `?limit=&offset=`; responses include `total` |
| Versioning | Breaking changes go to `/api/v2`; UI and server ship together, so `v1` is not kept forever |

The UI client lives in `src/app/src/api/client.ts`; add one typed function per endpoint there.

## Endpoints

| Method | Path | Purpose | Status |
| --- | --- | --- | --- |
| `GET` | `/health` | Liveness and server version | **Implemented** |
| `GET` | `/status` | Collector health, gaps, database size, NEU, unmapped IDs, API key state | **Implemented** |
| `GET` | `/flips/{type}` | Ranked opportunities; `type` = `forge` \| `craft` \| `npc` \| `bazaar` | **Implemented** |
| `GET` | `/flips/{type}/{id}` | One opportunity: recipe, ingredient prices, cost/profit sheet, alerts, guide | **Implemented** |
| `GET` | `/items/{id}` | Name, current Bazaar / AH / NPC prices and 7-day Bazaar averages | **Implemented** |
| `GET` | `/items/{id}/history` | Bucketed price history for charts; `?from=&to=&bucket=` | **Implemented** |
| `GET` | `/forge/slots` | Forge Tracker: slots, jobs, time left, finished alerts | **Implemented** |
| `GET` | `/forge/jobs` | Recent jobs with expected vs realised value (`?limit=`, max 200) | **Implemented** |
| `POST` | `/forge/jobs` | Record a manually started forge job | **Implemented** |
| `POST` | `/forge/jobs/{id}/collect` | Mark a job collected, freeing its slot | **Implemented** |
| `PUT` | `/forge/jobs/{id}/sale` | Record what the player actually received for the output (`{"value": 720000}`, `null` clears) | **Implemented** |
| `DELETE` | `/forge/jobs/{id}` | Remove a job (recorded by mistake, or from the history) | **Implemented** |
| `GET` | `/forge/plan` | What to start in the free slots within the budget (`?budget=&nextSessionIn=&slots=&hideAlerts=`) | **Implemented** |
| `GET` | `/forge/calibration` | Speed in use, fitted model, samples, suggested recipes | **Implemented** |
| `POST` | `/forge/calibration` | Add a calibration sample | **Implemented** |
| `DELETE` | `/forge/calibration/{id}` | Remove one sample (or all: `DELETE /forge/calibration`) | **Implemented** |
| `GET` | `/alerts` | Active alerts | Planned |
| `GET` | `/profile` | Effective personal context, forge speed and profile link state | **Implemented** |
| `POST` | `/profile/link` | Read the SkyBlock profile now (needs a username and the API key) | **Implemented** |
| `DELETE` | `/profile/link` | Forget the linked profile; manual settings apply again | **Implemented** |
| `GET` | `/settings` | Effective settings, where they come from, API key state (never the key) | **Implemented** |
| `PATCH` | `/settings` | JSON merge patch of the settings; validated, saved in the app | **Implemented** |
| `DELETE` | `/settings` | Drop the settings saved in the app, back to `config.yaml` | **Implemented** |
| `PUT` | `/settings/api-key` | Store a Hypixel API key (`{"key": "…", "issuedAt": null}`) | **Implemented** |
| `DELETE` | `/settings/api-key` | Forget the key entered in the app | **Implemented** |
| `POST` | `/database/export` | Export market history to a new file (`{"from": ms, "to": ms}`, both optional) | **Implemented** |
| `GET` | `/database/exports` | Export files, newest first; `GET /database/exports/{name}` downloads one | **Implemented** |
| `GET` | `/trades` | Trade journal, newest first (`?type=&limit=`, max 1000) | **Implemented** |
| `GET` | `/trades/summary` | Expected vs actual profit per flipper and per alert | **Implemented** |
| `POST` | `/trades` | Record a flip with today's estimate (`{"type": "forge", "id": "…", "runs": 2, "note": "…"}`) | **Implemented** |
| `PUT` | `/trades/{id}/result` | Enter what was paid and received (`actualCost`, `actualRevenue`, `finished`, `note`) | **Implemented** |
| `DELETE` | `/trades/{id}` | Remove a trade | **Implemented** |
| `GET` | `/backtest/{type}` | Replay a flipper over stored history (`?from=&to=&step=&horizon=&top=&hideAlerts=`) | **Implemented** |
| `POST` | `/database/import` | Merge a history file: JSON `{"path": "…"}` or a multipart upload (`file`) | **Implemented** |
| `GET` | `/icons` | Item icon index (item ID → atlas cell), `204` when no resource pack is loaded | **Implemented** |
| `GET` | `/icons/atlas.png` | The icon atlas; the UI asks for `?v=<builtAt>` and caches it | **Implemented** |
| `GET` | `/icons/candidates` | Hypixel SkyBlock packs found in the usual Minecraft folders, newest first | **Implemented** |
| `POST` | `/icons/import` | Build icons from a pack: JSON `{"path": "…"}` or a multipart upload (`file`) | **Implemented** |
| `DELETE` | `/icons` | Remove the icons; items show their letter again | **Implemented** |

### `GET /api/v1/health`

```json
{ "status": "UP", "version": "0.1.0-SNAPSHOT" }
```

### `GET /api/v1/status`

Polled by the UI every 10 seconds.

```json
{
  "collectors": [
    {
      "name": "BAZAAR",
      "intervalSeconds": 60,
      "lastRun": { "collector": "BAZAAR", "startedAt": 1794936024000, "finishedAt": 1794936025172,
                   "status": "OK", "rowsWritten": 412, "error": null },
      "lastSuccessAt": 1794936025172,
      "consecutiveFailures": 0
    }
  ],
  "gaps": [ { "collector": "BAZAAR", "gapStart": 1794930000000, "gapEnd": 1794936000000, "backfilled": false } ],
  "database": { "sizeBytes": 2052096, "items": 9459, "bazaarSnapshots": 4414, "auctionSales": 431 },
  "neu": { "loaded": true, "commit": "ac4e1ea…", "loadedAt": 1794936013798, "items": 8970,
           "recipes": { "CRAFTING": 2565, "FORGE": 124, "NPC_SHOP": 1095 } },
  "unmapped": { "count": 69, "sample": [ { "source": "BAZAAR", "sourceId": "BAZAAR_COOKIE", "lastSeen": 1794936024000, "seen": 3 } ] },
  "apiKey": { "state": "MISSING", "expiresAt": null }
}
```

| Field | Notes |
| --- | --- |
| `collectors[].lastRun.status` | `RUNNING`, `OK` or `FAILED`; `error` holds the exception text of a failed run |
| `collectors[].consecutiveFailures` | Since the last success in this server session; drives backoff |
| `gaps` | Latest 20 offline periods for history collectors |
| `unmapped.sample` | Top 20 IDs without a known NEU item, by times seen |
| `apiKey.state` | `MISSING`, `UNKNOWN_AGE`, `VALID`, `EXPIRING_SOON` (under 12 h left), `EXPIRED` |

### `GET /api/v1/flips/{type}`

`type` is `forge`, `craft`, `npc` or `bazaar`; other types return `404`. Query parameters filter and sort; unset values come from the config.

| Parameter | Example | Meaning |
| --- | --- | --- |
| `budget` | `50000000` | Hide recipes costing more; overrides `trading.budget` |
| `nextSessionIn` | `PT8H` or `8h` | Time until the player can refill a slot; overrides `forge.next-session-in` |
| `sort` | `profitPerSession` | `profitPerSession`, `profitPerHour`, `profitPerDay`, `netProfit`, `roi`. Default per type: forge `profitPerSession`, craft `netProfit`, npc and bazaar `profitPerDay` |
| `minVolume` | `100` | Hide outputs that trade fewer units per day |
| `includeLocked` | `true` | Also return recipes the player has not unlocked |
| `includeIncomplete` | `true` | Also return opportunities with a missing price (hidden by default) |
| `limit`, `offset` | `50`, `0` | Paging; `limit` 1–500 |
| `hideAlerts` | `FEW_SALES,WIDE_SPREAD` | Hide rows carrying any of these [alert](alerts.md) types (comma-separated or repeated; unknown names `400`). With `BULK_CRAFT`, a craft or forge flip whose plan needs bulk crafting is first planned again with fewer crafting levels (and, for crafts, its other recipes); it is hidden only when no plan fits an inventory |
| `search` | `mithril` | Only names containing this text, case-insensitive |
| `hotm` | `7` | Check HOTM requirements against this level (0–10) instead of the profile, for example to plan ahead |
| `priceSource` | `INSTANT` | Price Bazaar trades as `ORDER` (buy orders, sell offers) or `INSTANT` (insta-buy, insta-sell) for this request instead of Settings; also on the detail |
| `sellVenue` | `AUCTION` | Only flips whose output sells there: `BAZAAR`, `AUCTION` or `NPC` |
| `craftSteps` | `2` | Most crafting steps per flip, the final craft included (1–4): `1` buys every input, `2` lets an input be crafted once more. Unset: up to 4 (the planner's default). Used by craft and forge flips |

```json
{
  "total": 107,
  "generatedAt": 1794942704303,
  "settings": {
    "priceSource": "ORDER", "nextSessionInSeconds": 28800, "budget": null, "sort": "profitPerSession",
    "forgeSpeed": { "knownReduction": 0.2, "quickForgeReduction": 0.2, "moltenForge": false,
                    "calibration": "NONE", "calibrationFactor": 1.0, "calibrationFixedSeconds": 0, "provisional": true }
  },
  "items": [
    {
      "id": "REFINED_MITHRIL", "type": "FORGE", "name": "Refined Mithril",
      "outputId": "REFINED_MITHRIL", "outputCount": 1.0,
      "cost": 201600.0, "value": 760000.0, "fees": 9500.0, "netProfit": 548900.0, "roi": 2.72,
      "baseDurationSeconds": 21600, "durationSeconds": 17280,
      "profitPerHour": 114354.2, "sessions": 1, "profitPerSession": 548900.0,
      "sellVenue": "BAZAAR", "buyVenues": ["BAZAAR"],
      "liquidity": { "level": "HIGH", "dailyVolume": 31284.7 },
      "unlock": "MET", "complete": true,
      "alerts": []
    }
  ]
}
```

| Field | Notes |
| --- | --- |
| money fields | Per slot, per run, in coins; `value` is before fees |
| `unlock` | `MET`, `LOCKED` (hidden unless `includeLocked`) or `UNKNOWN` |
| `sellVenue`, `buyVenues` | Where the output sells best and where the inputs (crafted intermediates included) are bought: `BAZAAR`, `AUCTION`, `NPC` |
| `complete` | `false` if a price is missing; such rows are hidden unless `includeIncomplete`, and rank last |
| `profitPerHour` | `null` for craft, NPC and Bazaar (duration 0) |
| `fillSeconds` | Forge and craft with Bazaar orders (`ORDER`): about how long one run's orders take to fill at current volume if they stay the best. The slowest input buy order (quantity ÷ insta-sells per second) plus the sell offer (quantity ÷ insta-buys per second). `null` if nothing waits on an order |
| `runsPerDay`, `profitPerDay` | NPC and Bazaar, `null` elsewhere. NPC IDs are `shop:<NPC>:<item>` and `sell:<item>`; Bazaar IDs are item IDs and one run is one unit |
| `settings.priceSource` | Always `ORDER` for `bazaar` (spreads are order to order) |
| `settings.forgeSpeed` | `null` for craft |
| `alerts[]` | `{ type, message, itemId }`; types in [Alerts](alerts.md) |
| `volume` | `{ outputRunsPerDay, inputRunsPerDay, sellers }`: runs' worth of the output that sell a day (side the sale waits on; null if unknown or NPC), runs' worth of the scarcest bought input that can be bought a day (null if nothing is bought on a market), and the sell offers or BIN listings the sale queues behind (null for insta-sell and NPC) |

`GET /api/v1/flips/{type}/{id}` (the output item ID) returns `{ summary, sheet, unlock, guide, names }`: `sheet` lists each input with its quote (`venue`, `unitPrice`, `gross`, `priceAt`, `estimated`; for a crafted input, what buying would cost) and its `plan` (`method` `BUY`/`CRAFT`/`MISSING`, `cost`, `crafts`, `coins`, nested `inputs`), the recipe coins, the output quote and fees; `unlock.checks` lists each requirement; `guide` has `steps` and `logic`; `names` maps item IDs to display names. It takes `nextSessionIn`, `craftSteps` and `hideAlerts` like the list, so the detail matches the row it was opened from. Unknown types and IDs return `404`; bad parameters `400`.

### Forge Tracker

`POST /api/v1/forge/jobs`

```json
{ "slot": 1, "recipeId": "REFINED_MITHRIL", "remainingSeconds": 9000 }
```

| Field | Notes |
| --- | --- |
| `slot` | 1 to the configured slot count (`400` otherwise) |
| `recipeId` | Forge recipe output ID (`404` if unknown) |
| `startedAt` | Optional epoch ms; default now |
| `remainingSeconds` | Optional time left as shown in game; sets the end to now + remaining |

Returns `201` with the job; `409` if the slot already holds an uncollected job. A job:

```json
{
  "id": 1, "slot": 1, "recipeId": "REFINED_MITHRIL", "name": "Refined Mithril",
  "startedAt": 1794942704274, "expectedEndAt": 1794959984274, "remainingSeconds": 17280, "finished": false,
  "baseSeconds": 21600, "expectedCost": 200314.9, "expectedValue": 750131.1, "expectedProfit": 549816.1,
  "realisedValue": null, "realisedAt": null, "valueChange": null, "collectedAt": null, "source": "MANUAL",
  "soldValue": null, "soldProfit": null
}
```

`soldValue` is what the player entered as received (`PUT /forge/jobs/{id}/sale`); `soldProfit` is `soldValue − expectedCost`. Both stay `null` until a sale is entered; deleting a job removes it from the history.

`GET /api/v1/forge/slots` returns `{ slotCount, slotCountKnown, now, slots: [{ slot, job }], unassigned, alerts, timeZone }`. `timeZone` is the IANA zone the UI shows end times in (`display.time-zone`, else the computer's). `unassigned` holds open jobs in slots above the current slot count; `alerts` holds `FORGE_FINISHED` entries. Reading slots or jobs settles finished jobs (fills `realisedValue`).

### Forge planner

`GET /api/v1/forge/plan` returns `{ slots, budget, nextSessionInSeconds, picks: [{ slot, opportunity }], totalCost, totalProfitPerSession, limitedBy, candidates }`. `slots` are the empty or finished slots of the tracker, or `1..slots` when given. The planner takes the 15 best recipes by profit per session, then searches every mix (with repeats) that fits the budget for the highest total. A recipe fills at most one slot at `LOW` or `UNKNOWN` liquidity and two at `MEDIUM`, since copies compete for the same buyers. `limitedBy` is `BUDGET` when more money would have earned more, `SLOTS` when every slot is used, `NO_CANDIDATES` otherwise. Rows with `FEW_SALES` are skipped unless `hideAlerts` says otherwise.

### Calibration

`POST /api/v1/forge/calibration` with `{ "recipeId": "REFINED_MITHRIL", "observedSeconds": 8640 }` returns `201` with the sample (`calculatedSeconds`, `ratio`). `GET /api/v1/forge/calibration` returns `{ speed, fit: { kind, factor, fixedSeconds, samples, provisional }, samples, suggestions }`. See [Forge](forge.md#calibration-wizard).

### `GET /api/v1/profile`

`{ context, forgeSpeed, collectionsKnown, link }`: the effective HOTM level, forge slots, collection tiers, Quick Forge and Molten Forge state (with `moltenForgeSource` `ELECTION`, `CONFIG` or `UNKNOWN`), budget, next session, price source and fees. `context.source` is `PROFILE` while a linked profile supplies values, otherwise `APP` (settings saved in the app) or `CONFIG`. `link` is `{ linked, configured, lastError, lastAttemptAt }`; `linked` is the profile read last (`cuteName`, `profileNames`, `hotmLevel`, `quickForgeLevel`, `quickForgeReduction`, `collections`, `forge`, `fetchedAt`), or null when none matches the configured username and profile name.

### Profile linking

`POST /api/v1/profile/link` resolves the username through Mojang, reads `/v2/skyblock/profiles` with the API key and returns `{ profile, forge: { added, moved, collected, unknownRecipes } }`, the changes it made in the Forge Tracker (see [Forge](forge.md#forge-tracker)). The `PROFILE` collector does the same every `hypixel.profile-interval`. Errors carry a `kind`: `NOT_CONFIGURED` and `KEY_REJECTED` (400), `NOT_FOUND` (404, unknown player or profile), `RATE_LIMITED` (429), `UNAVAILABLE` (502).

### Settings

`GET /api/v1/settings` returns `{ settings, source, apiKey }`. `settings` has the shape of the matching `config.yaml` sections (`player`, `trading`, `forge`, `npc`, `bazaar`, `alerts`, `display`; field names in camelCase, durations as ISO-8601). `PATCH` takes a JSON merge patch (RFC 7386): only the fields that change, `null` to clear an optional value. The `player.collections` map is replaced as a whole when sent. Invalid values answer `400` with every problem in `problems`; nothing is saved then. See [Configuration](configuration.md#editing-in-the-app).

### Items

`GET /api/v1/items/{id}` returns `{ id, name, bazaar, auction, npcSellPrice, average7d }` (each `null` when unknown). `GET /api/v1/items/{id}/history` returns `{ id, name, from, to, bucketSeconds, bazaar: [{ t, buyPrice, sellPrice }], sales: [{ t, median, sales }], lowestBin: [{ t, price }] }`. Defaults: the last 7 days, buckets sized for about 400 points (at least a minute). Bazaar points are bucket averages; older periods come from the hourly summaries. Sale points are the median unit price of clean sales.

### Trade journal

`POST /api/v1/trades` prices the flip as the list does now and stores the expected cost and value (after fees) for all runs, plus the alert types it carried. A trade: `{ id, type, opportunityId, name, runs, startedAt, expectedCost, expectedValue, expectedProfit, alerts, actualCost, actualRevenue, actualProfit, difference, finishedAt, note }`. `actualProfit` is `actualRevenue − actualCost` (the expected cost while no cost is entered); `difference` is `actualProfit − expectedProfit`.

`GET /api/v1/trades/summary` returns `{ all, byType, byAlert, noAlerts }`, each `{ trades, withResult, expectedProfit, actualProfit, accuracy, profitable }`. `accuracy` is actual ÷ expected profit of the trades with a result. Forge Tracker jobs with a sold value count as forge trades (without alerts). Unknown flips answer `404`, bad values `400`.

### Backtest

`GET /api/v1/backtest/{type}` ranks opportunities at moments `from, from + step, …` up to `to` (at most 48) using only the prices stored then, with no live book. Each of the top `top` rows (1 to 50, default 20) is then sold at its venue at the prices of `t + horizon` (forge: `t +` the job time). Defaults: the last day, `step` and `horizon` `1h`, `step` at least 5 minutes. Sales after the newest stored snapshot are left out and counted in `beyondHistory`.

The result has `{ moments, skipped, beyondHistory, overall, byAlert, noAlerts, shortfall, items }`. Stats are `{ flips, predicted, realised, capture, profitable, rankCorrelation }`:

- `capture` is realised ÷ predicted profit;
- `rankCorrelation` is the mean Spearman correlation within a moment;
- `shortfall` lists the opportunities furthest below their prediction.

Inputs keep their price at `t`: buy orders are assumed to fill at once.

### Import and export

`POST /api/v1/database/export` returns `{ name, path, bytes, rows }`. Files are written to `<data-dir>/exports` and downloaded by name only. `POST /api/v1/database/import` returns `{ path, schemaVersion, rows }` with the new rows per table. `409` means another transfer is running; `400` a file that is not a HyFlip database or comes from a newer version. See [Database](database.md#import-and-export).

### Item icons

`POST /api/v1/icons/import` and `GET /api/v1/icons` return the index: `{ cell, columns, items, glint, source, client, fromPack, builtAt, ... }`. `items` maps an item ID to its cell in the atlas (`cell` px squares, `columns` per row); `glint` lists items drawn with the enchantment glint. `400` means the file is not the Hypixel SkyBlock pack. See [Data sources](data-sources.md#item-icons).

## Live updates

**Planned, post-MVP.** A server-sent events stream (`GET /api/v1/events`) can push new snapshots, alerts and forge-finished events instead of the UI polling.
