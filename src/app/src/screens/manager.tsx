/** Forge Manager: live slots, a plan for free slots, job history, and forge speed calibration. */
import { useContext, useEffect, useState } from "react";
import type { CalibrationOverview, ForgeJob } from "../api/client";
import {
  clockAt, coins, duration, ErrorLine, ForgeContext, parseCoins, parseDuration, pct, ProfileContext, Slot, Stat,
  useApi, useNow, usePolling, useStored, useToast,
} from "../ui/common";

export function ManagerScreen() {
  const [sub, setSub] = useStored<"tracker" | "calibration">("manager.sub", "tracker");
  return (
    <div className="page">
      <div className="seg">
        <button className={sub === "tracker" ? "on" : ""} onClick={() => setSub("tracker")}>Tracker</button>
        <button className={sub === "calibration" ? "on" : ""} onClick={() => setSub("calibration")}>Calibration</button>
      </div>
      {sub === "tracker" ? <Tracker /> : <Calibration />}
    </div>
  );
}

function useAct() {
  const toast = useToast();
  const { reload } = useContext(ForgeContext);
  return async (action: () => Promise<unknown>, done?: string, after?: () => void) => {
    try {
      await action();
      if (done) toast(done);
      reload();
      after?.();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  };
}

function Tracker() {
  const api = useApi();
  const { slots, reload } = useContext(ForgeContext);
  const now = useNow();
  const act = useAct();
  const history = usePolling(() => api.forgeJobs(30), 30_000, [api, slots], "manager:jobs");
  const recipes = usePolling(() => api.flips("forge", { includeLocked: true, includeIncomplete: true, limit: 500, sort: "netProfit" }), 0, [api],
    "manager:recipes");
  // Countdowns run on the server's clock: shift local time by the offset seen at the last fetch.
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    if (slots) setOffset(slots.now - Date.now());
  }, [slots]);
  const serverNow = now + offset;
  const zone = slots?.timeZone;
  const recipeOptions = (recipes.data?.items ?? []).slice().sort((a, b) => a.name.localeCompare(b.name));
  const free = (slots?.slots ?? []).filter((s) => !s.job);

  if (!slots) return <p className="muted">Loading forge slots…</p>;
  return (
    <>
      {!slots.slotCountKnown && (
        <p className="muted small">Showing {slots.slotCount} slots: the most seen in use. If you have more, set the count in Settings → Forge.</p>
      )}
      <div className="forge-slots">
        {slots.slots.map(({ slot, job }) => {
          const left = job ? Math.max(0, (job.expectedEndAt - serverNow) / 1000) : 0;
          const total = job ? Math.max(1, (job.expectedEndAt - job.startedAt) / 1000) : 1;
          return (
            <div key={slot} className={`fslot ${job ? (left === 0 ? "ready" : "busy") : "free"}`}>
              <div className="fslot-head">Slot {slot}{job?.source === "PROFILE_API" && <span className="chip from-profile" data-tip="Read from your SkyBlock profile.">Profile</span>}</div>
              {job ? (
                <>
                  <div className="fslot-item"><Slot item={job.recipeId} size={40} name={job.name} /> <span>{job.name}</span></div>
                  <div className="pbar"><span style={{ width: `${100 - (left / total) * 100}%` }} /></div>
                  <div className="fslot-time">{left === 0 ? "Ready to collect" : `${duration(left)} left`}</div>
                  <div className="muted small">{left === 0 ? "ended" : "ends"} {clockAt(job.expectedEndAt, zone)}</div>
                  {job.expectedProfit !== null && <div className="muted small">expected profit <span className="gain">{coins(job.expectedProfit)}</span></div>}
                  <div className="fslot-actions">
                    <button className={left === 0 ? "primary small" : "small"} onClick={() => void act(() => api.collectForgeJob(job.id), `${job.name} collected`)}
                      data-tip="Frees the slot. Type what it sold for in the history below.">
                      {left === 0 ? "Collected" : "Collected early"}
                    </button>
                    <button className="ghost small" onClick={() => {
                      if (window.confirm(`Remove ${job.name} from slot ${slot}? Use this for a job recorded by mistake.`)) void act(() => api.deleteForgeJob(job.id), "Removed");
                    }} data-tip="Remove a job recorded by mistake">Remove</button>
                  </div>
                </>
              ) : (
                <StartForm slot={slot} recipes={recipeOptions}
                  onStart={(recipeId, remainingSeconds) => void act(() => api.startForgeJob({ slot, recipeId, remainingSeconds }), `Started in slot ${slot}`)} />
              )}
            </div>
          );
        })}
      </div>
      {slots.unassigned.length > 0 && (
        <p className="muted small">{slots.unassigned.length} more job{slots.unassigned.length > 1 ? "s are" : " is"} running without a slot (more jobs than slots).</p>
      )}

      <PlanPanel free={free.length} onStart={(slot, recipeId) => void act(() => api.startForgeJob({ slot, recipeId }), `Started in slot ${slot}`)} />

      <h3>History: expected vs real</h3>
      {history.error && <ErrorLine text={history.error} onRetry={history.reload} />}
      {history.data && history.data.length === 0 && <p className="muted">No jobs recorded yet.</p>}
      {history.data && history.data.length > 0 && (
        <table className="flips">
          <thead>
            <tr>
              <th /><th>Recipe</th><th>Started</th>
              <th className="num">Cost</th>
              <th className="num"><span data-tip="What the output was worth when the job started.">Expected</span></th>
              <th className="num"><span data-tip="What the output was worth when the job ended.">At the end</span></th>
              <th className="num">Change</th>
              <th className="num"><span data-tip="What you actually got, after tax. It counts in the journal.">Sold for</span></th>
              <th className="num">Your profit</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {history.data.map((j) => (
              <tr key={j.id}>
                <td><Slot item={j.recipeId} size={36} name={j.name} /></td>
                <td>{j.name}</td>
                <td className="muted">{clockAt(j.startedAt, zone)}</td>
                <td className="num">{j.expectedCost === null ? "—" : coins(j.expectedCost)}</td>
                <td className="num">{j.expectedValue === null ? "—" : coins(j.expectedValue)}</td>
                <td className="num">{j.realisedValue === null ? <span className="muted">{j.finished ? "no price" : "running"}</span> : coins(j.realisedValue)}</td>
                <td className={`num ${(j.valueChange ?? 0) >= 0 ? "gain" : "loss"}`}>{j.valueChange === null ? "—" : coins(j.valueChange)}</td>
                <td className="num"><SoldInput job={j} onSave={(value) => void act(() => api.recordForgeSale(j.id, value), "Saved", history.reload)} /></td>
                <td className={`num ${(j.soldProfit ?? 0) >= 0 ? "gain" : "loss"}`}>{j.soldProfit === null ? "—" : coins(j.soldProfit)}</td>
                <td>
                  <button className="ghost small" aria-label={`Delete ${j.name}`} data-tip="Delete this job from the history" onClick={() => {
                    if (window.confirm(`Delete ${j.name} from the history?`)) void act(() => api.deleteForgeJob(j.id), "Deleted", history.reload);
                  }}>✕</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <button className="ghost small" onClick={reload}>Refresh</button>
    </>
  );
}

/** Coins received for the output, saved on Enter or when the field loses focus; empty clears it. */
function SoldInput({ job, onSave }: { job: ForgeJob; onSave: (value: number | null) => void }) {
  const saved = job.soldValue === null ? "" : String(Math.round(job.soldValue));
  const [text, setText] = useState(saved);
  useEffect(() => setText(saved), [saved]);
  const value = text.trim() === "" ? null : parseCoins(text);
  const invalid = text.trim() !== "" && value === null;
  const save = () => {
    if (!invalid && text !== saved) onSave(value);
  };
  return (
    <input value={text} size={7} placeholder="e.g. 720k" aria-label={`Sold value of ${job.name}`}
      className={`num-input ${invalid ? "bad" : ""}`} onChange={(e) => setText(e.target.value)} onBlur={save}
      onKeyDown={(e) => { if (e.key === "Enter") save(); }} />
  );
}

function StartForm(props: {
  slot: number;
  recipes: { id: string; name: string; durationSeconds: number }[];
  onStart: (recipeId: string, remainingSeconds?: number) => void;
}) {
  const [recipe, setRecipe] = useState("");
  const [remaining, setRemaining] = useState("");
  const remainingSeconds = remaining.trim() === "" ? undefined : parseDuration(remaining);
  const valid = recipe !== "" && remainingSeconds !== null;
  return (
    <form className="slot-form" onSubmit={(e) => {
      e.preventDefault();
      if (valid) {
        props.onStart(recipe, remainingSeconds ?? undefined);
        setRecipe("");
        setRemaining("");
      }
    }}>
      <div className="muted">Empty</div>
      <select value={recipe} onChange={(e) => setRecipe(e.target.value)} aria-label={`Recipe for slot ${props.slot}`}>
        <option value="">Pick a recipe…</option>
        {props.recipes.map((r) => <option key={r.id} value={r.id}>{r.name} ({duration(r.durationSeconds)})</option>)}
      </select>
      <input value={remaining} onChange={(e) => setRemaining(e.target.value)} placeholder="time left (optional)"
        className={remainingSeconds === null ? "bad" : ""} aria-label="Time left in game" data-tip="If it was started earlier, type the time the game shows, like 3h 20m." />
      <button type="submit" className="small" disabled={!valid}>Started</button>
    </form>
  );
}

/** Best recipes for the free slots within the budget, each with a start button. */
function PlanPanel({ free, onStart }: { free: number; onStart: (slot: number, recipeId: string) => void }) {
  const api = useApi();
  const [budgetText, setBudgetText] = useStored("manager.budget", "");
  const budget = budgetText.trim() === "" ? undefined : parseCoins(budgetText);
  const plan = usePolling(() => api.forgePlan({ budget: budget ?? undefined }), 0, [api, free, budget]);
  const p = plan.data;
  return (
    <section className="card plan-card">
      <div className="section-head">
        <h3>Plan for free slots</h3>
        <label className={`field ${budget === null ? "bad" : ""}`} data-tip="Most coins to spend on the plan. Empty uses Settings.">
          Budget <input value={budgetText} size={8} placeholder="settings" onChange={(e) => setBudgetText(e.target.value)} />
        </label>
        {p && <span className="muted small">next session in {duration(p.nextSessionInSeconds)}</span>}
      </div>
      {plan.error && <ErrorLine text={plan.error} onRetry={plan.reload} />}
      {p && p.slots.length === 0 && <p className="muted">Every slot is busy. The plan fills in when one frees up.</p>}
      {p && p.slots.length > 0 && p.picks.length === 0 && (
        <p className="muted">Nothing profitable fits{p.budget !== null ? ` a budget of ${coins(p.budget)}` : ""}.</p>
      )}
      {p && p.picks.length > 0 && (
        <table className="flips">
          <thead><tr><th style={{ width: 60 }}>Slot</th><th style={{ width: 44 }} /><th>Recipe</th><th className="num">Time</th><th className="num">Cost</th><th className="num">Per session</th><th /></tr></thead>
          <tbody>
            {p.picks.map(({ slot, opportunity: o }) => (
              <tr key={slot}>
                <td>{slot}</td>
                <td><Slot item={o.outputId} size={36} name={o.name} /></td>
                <td>{o.name}</td>
                <td className="num">{duration(o.durationSeconds)}</td>
                <td className="num">{coins(o.cost)}</td>
                <td className="num gain">{coins(o.profitPerSession)}</td>
                <td><button className="small" onClick={() => onStart(slot, o.id)} data-tip="Start it in game, then press this to track it.">Started</button></td>
              </tr>
            ))}
            <tr className="total">
              <td colSpan={4}>
                Total{p.limitedBy === "BUDGET" ? " (the budget limits the picks)" : ""}
                {p.picks.length < p.slots.length ? ` · ${p.slots.length - p.picks.length} slot(s) left empty` : ""}
              </td>
              <td className="num">{coins(p.totalCost)}</td>
              <td className="num gain">{coins(p.totalProfitPerSession)}</td>
              <td />
            </tr>
          </tbody>
        </table>
      )}
    </section>
  );
}

const FIT_TEXT: Record<CalibrationOverview["fit"]["kind"], string> = {
  NONE: "Not calibrated: durations use only Quick Forge and Molten Forge.",
  PERCENTAGE: "Your forge is a further percentage faster than the known perks explain.",
  FIXED: "Your forge saves a fixed amount of time per run beyond the known perks.",
  INCONSISTENT: "The samples do not fit one pattern. Re-check one of them.",
};

/** Time three recipes of different length; the server fits the extra speed. */
function Calibration() {
  const api = useApi();
  const { reload: reloadProfile } = useContext(ProfileContext);
  const overview = usePolling(() => api.calibration(), 0, [api], "manager:calibration");
  const toast = useToast();
  const act = async (action: () => Promise<unknown>, done: string) => {
    try {
      await action();
      toast(done);
      overview.reload();
      reloadProfile();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  };
  if (overview.error) return <ErrorLine text={overview.error} onRetry={overview.reload} />;
  if (!overview.data) return <p className="muted">Loading…</p>;
  const { speed, fit, samples, suggestions } = overview.data;
  return (
    <>
      <div className="stats wide">
        <Stat label="Quick Forge" value={speed.quickForgeReduction === null ? "not set" : `−${pct(speed.quickForgeReduction)}`}
          tip="From your profile's HOTM tree, or Settings → Forge." />
        <Stat label="Molten Forge" value={speed.moltenForge ? "active (−25%)" : "off"} tip="The mayor perk; read from the election, or Settings." />
        <Stat label="Known speed-up" value={`−${pct(speed.knownReduction)}`} />
        <Stat label="Calibration" value={fit.kind === "NONE" ? "none" : fit.kind === "PERCENTAGE" ? `× ${fit.factor.toFixed(3)}` : fit.kind === "FIXED" ? `− ${duration(fit.fixedSeconds)}` : "inconsistent"}
          tip={FIT_TEXT[fit.kind]} />
      </div>
      <p className="muted">
        {FIT_TEXT[fit.kind]}
        {fit.kind !== "NONE" && fit.provisional && " Provisional: add samples with different base durations."}
      </p>
      <h3>Measure</h3>
      <p className="muted small">
        Start one of these (or use one already running) and type how long it really takes in your game. Three
        different lengths tell a percentage from a fixed saving.
      </p>
      <table className="flips">
        <thead><tr><th /><th>Recipe</th><th className="num">Base</th><th className="num">With known perks</th><th>Your time</th></tr></thead>
        <tbody>
          {suggestions.map((s) => (
            <MeasureRow key={s.recipeId} {...s} onSave={(seconds) => void act(() => api.addCalibrationSample(s.recipeId, seconds), "Sample saved")} />
          ))}
        </tbody>
      </table>
      {samples.length > 0 && (
        <>
          <h3>Samples</h3>
          <table className="flips">
            <thead><tr><th>Recipe</th><th className="num">Calculated</th><th className="num">Observed</th><th className="num">Ratio</th><th /></tr></thead>
            <tbody>
              {samples.map((s) => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td className="num">{duration(s.calculatedSeconds)}</td>
                  <td className="num">{duration(s.observedSeconds)}</td>
                  <td className="num">{s.ratio.toFixed(3)}</td>
                  <td><button className="ghost small" onClick={() => void act(() => api.deleteCalibrationSample(s.id), "Removed")}>Remove</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}

function MeasureRow(props: { recipeId: string; name: string; baseSeconds: number; calculatedSeconds: number; onSave: (seconds: number) => void }) {
  const [text, setText] = useState("");
  const seconds = parseDuration(text);
  return (
    <tr>
      <td><Slot item={props.recipeId} size={36} name={props.name} /></td>
      <td>{props.name}</td>
      <td className="num">{duration(props.baseSeconds)}</td>
      <td className="num">{duration(props.calculatedSeconds)}</td>
      <td>
        <form className="inline" onSubmit={(e) => {
          e.preventDefault();
          if (seconds) {
            props.onSave(seconds);
            setText("");
          }
        }}>
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder="e.g. 4h 30m" size={10}
            className={text !== "" && seconds === null ? "bad" : ""} aria-label={`Observed time of ${props.name}`} />
          <button type="submit" className="small" disabled={!seconds}>Save</button>
        </form>
      </td>
    </tr>
  );
}
