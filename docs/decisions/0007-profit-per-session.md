# 0007. Rank flips by profit per session

- Status: Accepted
- Date: 2026-11-17

## Context

Profit per hour favours recipes that finish in seconds, but a player refills a forge slot only when they are online. A 30-second recipe and a 6-hour recipe both earn one cycle if the player is away for 8 hours. The design doc proposed profit per session; the exact definition and the other metrics were open.

## Decision

- **Profit per session** = net profit ÷ sessions, where sessions = ⌈real duration ÷ time to next session⌉ (at least 1). A run that finishes before the next session earns its full profit for that session; a longer run spreads its profit over every session it keeps the slot busy.
- It is the default ranking metric. Profit per hour, profit per run and ROI remain selectable.
- **Net profit** = output value − sell fees − (ingredient cost + recipe coins). Prices follow `trading.price-source`; buying has no fee, selling pays the Bazaar tax or the Auction House listing fee and claim tax.
- **ROI** = net profit ÷ cost.
- **Liquidity** compares the output's daily volume with the quantity one run produces: HIGH ≥ 20×, MEDIUM ≥ 3×, otherwise LOW.
- Every figure is per slot and per run. Filling several slots with the same recipe multiplies cost and output; liquidity shows whether the market can take it.

## Consequences

- Short recipes no longer dominate the ranking.
- The ranking depends on the time to the next session (`forge.next-session-in`, or the UI field).
- Fee rates are config values and must be checked in game.
