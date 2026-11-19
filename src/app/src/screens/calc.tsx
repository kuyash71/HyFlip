/** Flip Calc: splits a budget across forge, Bazaar, craft and NPC flips (see calc/advisor.ts). */
import { useContext, useMemo, useState } from "react";
import type { FlipList, FlipType, Settings } from "../api/client";
import { solve, type Candidate, type Method as CalcMethod, type Plan, type Risk } from "../calc/advisor";
import { parseCandidateId, toCandidate } from "../calc/candidates";
import {
  ALERT_LABEL as TAG_LABEL, AlertTile, clockIn, coins, ErrorLine, ForgeContext, KeptContext, parseCoins, pct, ProfileContext,
  Slot, Stat, useApi, usePolling, useStored, useToast, VENUE_LABEL,
} from "../ui/common";
import { PixelIcon } from "../ui/pixel";

const H = 3600;
const METHODS: FlipType[] = ["forge", "bazaar", "craft", "npc"];
/** Best rows per flipper the solver looks at. */
const PER_METHOD = 150;

interface Source {
  type: FlipType;
  list: FlipList;
}

const METHOD_INFO: Record<CalcMethod, { label: string; tip: string }> = {
  forge: { label: "Forge", tip: "Jobs in your free forge slots. The coins stay in the job until it ends and sells." },
  bazaar: { label: "Bazaar", tip: "Buy orders, then sell offers. While you stay online the same coins go round again." },
  craft: { label: "Craft", tip: "Buy the inputs, craft, sell. Quick, and the coins go round again while you are online." },
  npc: { label: "NPC", tip: "Buy from NPC shops (640 of each item a day) or sell to them." },
};

/** Spread slider stops: the most of the budget one flip may take (1 = no limit). */
export const SPREAD_STEPS = [1, 0.5, 0.33, 0.25, 0.2, 0.15, 0.1, 0.05];

export function spreadText(share: number, budget: number | null): string {
  if (share >= 1) return "No limit";
  const pct = `${Math.round(share * 100)}%`;
  return budget !== null && budget > 0 ? `≤ ${pct} · ${coins(budget * share)} an item` : `≤ ${pct} an item`;
}

const RISK_INFO: Record<Risk, string> = {
  safe: "Only what should sell before you need the coins. Skips Price moved, Manipulated? and Few sales.",
  balanced: "Some stock may be left at the deadline if it should sell soon after. Skips Manipulated?.",
  bold: "Holds more stock for more profit, and takes tagged flips at their track record.",
};

const HORIZONS = [1, 4, 8, 12, 24, 48];
const ALERT_LABEL: Record<string, string> = Object.fromEntries(Object.entries(TAG_LABEL).map(([k, v]) => [k, `“${v}”`]));
const ONLINE: [number, string][] = [[0, "Offline"], [0.5, "30m"], [1, "1h"], [2, "2h"], [4, "4h"]];

function howTo(c: Candidate, units: number, capital: number, src: Opp | undefined): string {
  const n = units.toLocaleString("en-US");
  const sells = src?.sellVenue ? VENUE_LABEL[src.sellVenue] : "the market";
  const atOnce = Math.max(1, Math.floor(capital / c.unitCost + 1e-9));
  const rounds = atOnce < units;
  switch (c.method) {
    case "forge":
      return `Start ${n} forge job${units > 1 ? "s" : ""}, then sell on the ${sells}`;
    case "bazaar":
      return rounds ? `Buy orders for ${n} over your session, ${atOnce} at a time, then sell offers` : `Buy order for ${n}, then sell offers`;
    case "craft":
    {
      // A unit is one craft, which can make several items (40 for some recipes): say both.
      const perCraft = Math.max(1, src?.outputCount ?? 1);
      const times = `${n} time${units > 1 ? "s" : ""}`;
      const items = perCraft > 1 ? ` (${(units * perCraft).toLocaleString("en-US")} items, ${perCraft} a craft)` : "";
      return rounds
        ? `Craft ${times} over your session${items}, ${atOnce} at a time, sell on the ${sells}`
        : `Buy the inputs, craft ${times}${items}, sell on the ${sells}`;
    }
    case "npc":
      return src?.buyVenues.includes("NPC") ? `Buy ${n} from the NPC, sell on the ${sells}` : `Buy ${n} on the Bazaar, sell to the NPC`;
  }
}

type Opp = FlipList["items"][number];

/** Parses the most-flips field: empty means no limit (0), a whole number of 1 or more a limit, anything else null. */
export function parseMaxFlips(text: string): number | null {
  const t = text.trim();
  if (t === "") return 0;
  if (!/^\d+$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 ? n : null;
}
/** The last plan, kept while the app runs so it is still there after visiting other tabs. */
let lastResult: { plan: Plan | null; inputs: string } | null = null;

export function CalcScreen({ onOpen }: { onOpen: (t: FlipType, id: string) => void }) {
  const api = useApi();
  const { slots, reload: reloadSlots } = useContext(ForgeContext);
  const { profile } = useContext(ProfileContext);
  const kept = useContext(KeptContext);
  const toast = useToast();
  const settings = usePolling<Settings>(() => api.settings().then((v) => v.settings), 0, [api], "calc:settings");
  // 0 = any depth; 1 = buy every input; 2 = inputs may be crafted once. Only craft flips use it.
  const [craftSteps, setCraftSteps] = useStored("calc.craftSteps", 0);
  const [maxFlipsText, setMaxFlipsText] = useStored("calc.maxFlipsText", "");
  const parsedMaxFlips = parseMaxFlips(maxFlipsText);
  const maxFlips = parsedMaxFlips ?? 0;
  const sources = usePolling<Source[]>(
    () => Promise.all(METHODS.map(async (type) => ({
      type,
      list: await api.flips(type, {
        limit: PER_METHOD,
        includeLocked: type === "forge" || type === "craft",
        craftSteps: type === "craft" && craftSteps > 0 ? craftSteps : undefined,
        // Bulk crafts are left out of plans, so ask for the plan of each flip that fits an inventory.
        hideAlerts: type === "craft" || type === "forge" ? "BULK_CRAFT" : undefined,
      }),
    }))),
    120_000, [api, craftSteps], `calc:sources:${craftSteps}`);
  const [starting, setStarting] = useState(false);
  const [budgetText, setBudgetText] = useStored("calc.budget", "10m");
  const [hours, setHours] = useStored("calc.hours", 8);
  const [online, setOnline] = useStored("calc.online", 1);
  const [methods, setMethods] = useStored<CalcMethod[]>("calc.methods", ["forge", "bazaar", "craft", "npc"]);
  const [risk, setRisk] = useStored<Risk>("calc.risk", "balanced");
  const [spreadStep, setSpreadStep] = useStored("calc.spread", 0);
  const maxShare = SPREAD_STEPS[Math.min(Math.max(0, spreadStep), SPREAD_STEPS.length - 1)]!;
  const linked = profile?.link.linked ?? null;
  const purse = linked?.purse ?? null;
  const budget = parseCoins(budgetText);
  const free = (slots?.slots ?? []).filter((s) => !s.job);
  const linkedNow = profile?.context.source === "PROFILE";
  const bazaarShare = settings.data?.bazaar.marketShare ?? 0.05;
  const { candidates, byId } = useMemo(() => {
    const out: Candidate[] = [];
    const map = new Map<string, Opp>();
    for (const src of sources.data ?? []) {
      for (const o of src.list.items) {
        if (!o.complete || o.netProfit <= 0) continue;
        const c = toCandidate(src.type, o, src.list.extras?.[o.id], { bazaarShare, kept, strictUnlocks: linkedNow });
        out.push(c);
        map.set(c.id, o);
      }
    }
    return { candidates: out, byId: map };
  }, [sources.data, bazaarShare, kept, linkedNow]);
  // The plan is only worked out when the player presses CALCULATE: solving takes up to a second, and a plan that
  // changes under the cursor while inputs are typed is hard to read.
  const inputs = JSON.stringify([budget, hours, online, [...methods].sort(), risk, maxShare, free.length, craftSteps, maxFlips]);
  const [result, setStoredResult] = useState<{ plan: Plan | null; inputs: string } | null>(() => lastResult);
  const setResult = (r: { plan: Plan | null; inputs: string }) => {
    lastResult = r;
    setStoredResult(r);
  };
  const [calculating, setCalculating] = useState(false);
  const plan = result?.plan ?? null;
  const stale = result !== null && result.inputs !== inputs;
  const ready = budget !== null && budget > 0 && parsedMaxFlips !== null && sources.data !== undefined && sources.data !== null;
  const calculate = () => {
    if (!ready || calculating) return;
    setCalculating(true);
    // Let the button show its pressed state before the solver blocks the thread.
    setTimeout(() => {
      try {
        setResult({
          plan: solve(candidates, {
            budget: budget!, horizonSeconds: hours * H, onlineSeconds: online * H, methods, freeSlots: free.length, risk, maxShare,
            maxFlips: maxFlips > 0 ? maxFlips : undefined,
          }),
          inputs,
        });
      } finally {
        setCalculating(false);
      }
    }, 30);
  };
  const toggle = (m: CalcMethod) =>
    setMethods((ms) => (ms.includes(m) ? (ms.length > 1 ? ms.filter((x) => x !== m) : ms) : [...ms, m]));
  const startForge = async (candidateId: string, name: string, units: number) => {
    const targets = free.slice(0, units);
    const { id } = parseCandidateId(candidateId);
    setStarting(true);
    try {
      for (const t of targets) await api.startForgeJob({ slot: t.slot, recipeId: id });
      toast(`${name} started in slot${targets.length > 1 ? "s" : ""} ${targets.map((t) => t.slot).join(", ")}`);
    } catch (e) {
      toast(`Could not start it: ${e instanceof Error ? e.message : String(e)}`, "error");
    } finally {
      setStarting(false);
      reloadSlots();
    }
  };
  const byMethod = (m: CalcMethod) => plan?.allocations.filter((a) => a.candidate.method === m).reduce((x, a) => x + a.capital, 0) ?? 0;
  const confidence = plan && plan.fullProfit > 0 ? plan.expectedProfit / plan.fullProfit : 0;
  const lastBack = plan?.allocations.reduce((x, a) => Math.max(x, Math.min(a.cashOutSeconds, hours * H * 4)), 0) ?? 0;

  return (
    <div className="page calc-page">
      <div className="card calc-inputs">
        <button className={`calc-go ${stale ? "stale" : ""}`} onClick={calculate} disabled={!ready || calculating}
          data-tip={!ready ? (budget === null ? "Type a budget first." : "Waiting for the flip lists.")
            : stale ? "The inputs changed since the last plan. Press to work it out again." : "Work out the best mix for these inputs."}>
          <span className="calc-go-text">{calculating ? "Calculating…" : "Calculate"}</span>
          {stale && !calculating && <span className="calc-go-sub">inputs changed</span>}
        </button>
        <div className="ci-row">
          <div className={`ci-field ${budget === null ? "bad" : ""}`}>
            <span className="ci-label" data-tip="Coins you want to put to work. Type 500k, 2.5m or 1b.">Budget</span>
            <div className="ci-control">
              <input value={budgetText} onChange={(e) => setBudgetText(e.target.value)} size={9} aria-label="Budget"
                onKeyDown={(e) => { if (e.key === "Enter") calculate(); }} />
              {purse !== null ? (
                <button className="ghost small" onClick={() => setBudgetText(String(Math.floor(purse)))}
                  data-tip="Your purse, read from your SkyBlock profile.">Use purse · {coins(purse)}</button>
              ) : <span className="muted small">{linked ? "Purse not shared by the API" : "Link your account to use your purse"}</span>}
            </div>
          </div>
          <div className="ci-field">
            <span className="ci-label" data-tip="When you want the coins back. Flips that cannot sell by then count for less.">Coins back within</span>
            <div className="seg">
              {HORIZONS.map((h) => <button key={h} className={hours === h ? "on" : ""} onClick={() => setHours(h)}>{h}h</button>)}
            </div>
          </div>
          <div className="ci-field">
            <span className="ci-label" data-tip="How long you stay online to refill orders. While you play, Bazaar and craft coins go round more than once.">Online for</span>
            <div className="seg">
              {ONLINE.map(([h, label]) => <button key={h} className={online === h ? "on" : ""} onClick={() => setOnline(h)}>{label}</button>)}
            </div>
          </div>
        </div>
        <div className="ci-row">
          <div className="ci-field">
            <span className="ci-label" data-tip="Pick one or more. The plan only uses these.">Methods</span>
            <div className="method-tiles">
              {(Object.keys(METHOD_INFO) as CalcMethod[]).map((m) => (
                <button key={m} className={`mtile ${methods.includes(m) ? "on" : ""}`} aria-pressed={methods.includes(m)}
                  onClick={() => toggle(m)} data-tip={METHOD_INFO[m].tip} data-tip-title={METHOD_INFO[m].label}>
                  <PixelIcon name={m} size={26} />
                  <span>{METHOD_INFO[m].label}</span>
                  <span className="mtile-check" aria-hidden="true">{methods.includes(m) ? "✔" : ""}</span>
                </button>
              ))}
            </div>
          </div>
          <div className="ci-field">
            <span className="ci-label">Risk</span>
            <div className="seg">
              {(["safe", "balanced", "bold"] as Risk[]).map((r) => (
                <button key={r} className={risk === r ? "on" : ""} onClick={() => setRisk(r)} data-tip={RISK_INFO[r]}
                  data-tip-title={r.charAt(0).toUpperCase() + r.slice(1)}>
                  {r.charAt(0).toUpperCase() + r.slice(1)}
                </button>
              ))}
            </div>
          </div>
          <div className="ci-field spread-field">
            <span className="ci-label" data-tip-title="Spread"
              data-tip="Most of the budget one item may be worth in the plan, counted at full price. Slide right to buy fewer of each item and more different items: less riding on one, a little less expected profit.">
              Spread
            </span>
            <div className="spread">
              <input type="range" min={0} max={SPREAD_STEPS.length - 1} step={1} value={SPREAD_STEPS.indexOf(maxShare)}
                aria-label="Most of the budget one item may be worth" aria-valuetext={spreadText(maxShare, budget)}
                onChange={(e) => setSpreadStep(Number(e.target.value))} />
              <span className="spread-value small">{spreadText(maxShare, budget)}</span>
            </div>
          </div>
          <div className="ci-field">
            <span className="ci-label" data-tip-title="Craft steps"
              data-tip="Most crafting steps per craft flip. 1: buy every input and craft the item once. 2: an input may also be crafted from cheaper parts first. Any: up to 4 steps, the cheapest way.">
              Craft steps
            </span>
            <div className="seg">
              {([[1, "1"], [2, "2"], [0, "Any"]] as const).map(([v, label]) => (
                <button key={v} className={craftSteps === v ? "on" : ""} onClick={() => setCraftSteps(v)}>{label}</button>
              ))}
            </div>
          </div>
          <div className={`ci-field ${parsedMaxFlips === null ? "bad" : ""}`}>
            <span className="ci-label" data-tip-title="Most flips"
              data-tip="How many different flips the plan may use, for example 3. Empty: no limit. Fewer flips is less to keep track of, for a little less profit.">
              Most flips
            </span>
            <div className="ci-control">
              <input value={maxFlipsText} onChange={(e) => setMaxFlipsText(e.target.value)} size={5} aria-label="Most flips"
                placeholder="no limit" inputMode="numeric" onKeyDown={(e) => { if (e.key === "Enter") calculate(); }} />
            </div>
          </div>
          <div className="ci-profile">
            {profile?.context.hotmLevel !== null && profile?.context.hotmLevel !== undefined && (
              <span className={`chip ${linked ? "from-profile" : ""}`} data-tip="Locked recipes are left out.">HOTM {profile.context.hotmLevel}</span>
            )}
            <span className="chip" data-tip="Empty slots in the Forge Manager.">{free.length} of {slots?.slots.length ?? 0} forge slots free</span>
            {profile?.forgeSpeed.quickForgeReduction ? (
              <span className={`chip ${linked ? "from-profile" : ""}`} data-tip="Quick Forge shortens every forge job.">Quick Forge −{pct(profile.forgeSpeed.quickForgeReduction)}</span>
            ) : null}
            {!linked && <span className="muted small">Not linked: unlocks come from Settings.</span>}
          </div>
        </div>
      </div>

      {sources.error && <ErrorLine text={`Could not load flips: ${sources.error}`} onRetry={sources.reload} />}
      {!sources.data && !sources.error ? (
        <p className="empty muted">Loading flips from all four flippers…</p>
      ) : budget === null ? (
        <p className="empty">Type a budget like 500k, 2.5m or 1b.</p>
      ) : result === null ? (
        <p className="empty">Set your budget and methods, then press <b>CALCULATE</b>.</p>
      ) : !plan || plan.allocations.length === 0 ? (
        <p className="empty">Nothing worth doing with {coins(budget ?? 0)} in {hours}h at this risk level. Try a longer time or more methods.</p>
      ) : (
        <>
          <div className="stats wide calc-stats">
            <Stat label={`Expected back by ${clockIn(hours * H)}`} value={`+${coins(plan.expectedProfit)}`} tone="gain"
              tip="Profit expected back as coins by then: after tax, times each flip's chance to sell in time and its track record." />
            <Stat label="If everything sells" value={`+${coins(plan.fullProfit)}`}
              tip="Profit if every unit sells at today's price. The real result is usually between the two." />
            <Stat label={`Coins in use, of ${coins(budget)}`} value={coins(plan.capital)}
              tip="Most coins tied up at one time. Coins that go round more than once count once. What is left was not worth risking." />
            <Stat label="Confidence" value={pct(confidence)}
              tip="Expected ÷ if everything sells. Low means much depends on selling in time." />
            <Stat label="Last coins back" value={lastBack > hours * H ? `after ${hours}h` : `~${clockIn(lastBack)}`}
              tip="About when the last unit sells, if sales keep today's pace." />
          </div>

          <div className="mix" aria-label="Coins by method">
            <div className="mix-bar">
              {(Object.keys(METHOD_INFO) as CalcMethod[]).filter((m) => byMethod(m) > 0).map((m) => (
                <span key={m} className={`mix-seg m-${m}`} style={{ flexGrow: byMethod(m) }}
                  data-tip={`${coins(byMethod(m))} coins in ${METHOD_INFO[m].label}`} />
              ))}
              {plan.left > 0 && <span className="mix-seg m-left" style={{ flexGrow: plan.left }} data-tip={`${coins(plan.left)} kept as coins: nothing else was worth it`} />}
            </div>
            <div className="mix-legend small">
              {(Object.keys(METHOD_INFO) as CalcMethod[]).filter((m) => byMethod(m) > 0).map((m) => (
                <span key={m}><i className={`m-${m}`} />{METHOD_INFO[m].label} <b className="fg">{coins(byMethod(m))}</b></span>
              ))}
              {plan.left > 0 && <span><i className="m-left" />Kept <b className="fg">{coins(plan.left)}</b></span>}
            </div>
          </div>

          <table className="flips calc-plan">
            <thead>
              <tr>
                <th style={{ width: 28 }}>#</th>
                <th style={{ width: 34 }} />
                <th>What to do</th>
                <th className="num"><span data-tip="Units over the whole plan.">Units</span></th>
                <th className="num"><span data-tip="Coins tied up at the peak.">Coins in</span></th>
                <th className="num"><span data-tip="Profit expected back as coins by your deadline.">Expected</span></th>
                <th><span data-tip="Chance that every unit is sold by your deadline, at today's pace.">Sells in time</span></th>
                <th className="num"><span data-tip="About when the last unit sells.">Coins back</span></th>
                <th />
              </tr>
            </thead>
            <tbody>
              {plan.allocations.map((a, i) => {
                const c = a.candidate;
                const src = byId.get(c.id);
                const ref = parseCandidateId(c.id);
                const chance = a.sellAllChance;
                return (
                  <tr key={c.id} onClick={() => onOpen(ref.type, ref.id)}>
                    <td className="muted">{i + 1}</td>
                    <td><span data-tip={METHOD_INFO[c.method].tip} data-tip-title={METHOD_INFO[c.method].label}><PixelIcon name={c.method} size={24} /></span></td>
                    <td>
                      <div className="plan-item">
                        <Slot item={src?.outputId} size={36} name={c.name} />
                        <div className="plan-text">
                          <div className="item-name">{c.name}</div>
                          <div className="muted small">{howTo(c, a.units, a.capital, src)}</div>
                        </div>
                        <div className="row-tags">{[...new Map((src?.alerts ?? []).map((al) => [al.type, al])).values()].map((al) => <AlertTile key={al.type} a={al} />)}</div>
                      </div>
                    </td>
                    <td className="num">{a.units.toLocaleString("en-US")}</td>
                    <td className="num">{coins(a.capital)}</td>
                    <td className="num gain strong" data-tip={`If all ${a.units.toLocaleString("en-US")} sell: +${coins(a.fullProfit)}`}>+{coins(a.expectedProfit)}</td>
                    <td>
                      <div className="chance" data-tip={chance > 0.9 ? "Should all sell in time." : chance > 0.5 ? "Most likely sells in time; some may be left." : "Part of it will probably still be unsold by then."}>
                        <span className="chance-bar"><span style={{ width: `${Math.round(chance * 100)}%` }} className={chance > 0.9 ? "ok" : chance > 0.5 ? "mid" : "low"} /></span>
                        <span className="small">{pct(chance)}</span>
                      </div>
                    </td>
                    <td className="num small">{a.cashOutSeconds > hours * H ? <span className="loss">after {hours}h</span> : `~${clockIn(a.cashOutSeconds)}`}</td>
                    <td onClick={(e) => e.stopPropagation()}>
                      {c.method === "forge" ? (
                        <button className="primary small" disabled={starting || free.length === 0} onClick={() => void startForge(c.id, c.name, a.units)}
                          data-tip={`Start in slot${a.units > 1 ? "s" : ""} ${free.slice(0, a.units).map((s) => s.slot).join(", ")} and track it in the Forge Manager.`}>Start</button>
                      ) : (
                        <button className="small" onClick={() => onOpen(ref.type, ref.id)} data-tip="Open it in its flipper for the recipe and prices.">Open</button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}

      {plan && plan.excluded.length > 0 && (
        <details className="card calc-out">
          <summary><b>Left out</b> <span className="muted small">({plan.excluded.length}) and why</span></summary>
          <ul>
            {plan.excluded.map((x) => (
              <li key={x.candidate.id}>
                <PixelIcon name={x.candidate.method} size={18} /> <b>{x.candidate.name}</b> <span className="muted">· {x.reason.replace(/\b[A-Z]+(?:_[A-Z]+)+\b|\bMANIPULATION\b/g, (t) => ALERT_LABEL[t] ?? t)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}

      <details className="card calc-how">
        <summary><b>How Flip Calc decides</b></summary>
        <ol>
          <li><b>Profit per unit</b>, after tax, times the share flips with the same tags actually made in your journal (a default until it has results).</li>
          <li><b>Chance to sell in time</b>: each unit waits for buyers at today's pace; the 50th unit is less likely to sell by your deadline than the 1st.</li>
          <li>Unsold stock still counts, less the longer it would wait and the more its price swings. <b>Risk</b> sets how much.</li>
          <li><b>Spread</b> caps how much of one item the plan buys (units × price, as a share of the budget), so it is split over more items.</li>
          <li>Recipes behind a collection, slayer or HOTM level you have not reached are left out, and so are ones whose requirement cannot be checked.</li>
          <li>It then tries every forge mix that fits your free slots and fills the rest of the budget with the best profit per coin, swapping until nothing better fits.</li>
        </ol>
      </details>
    </div>
  );
}
