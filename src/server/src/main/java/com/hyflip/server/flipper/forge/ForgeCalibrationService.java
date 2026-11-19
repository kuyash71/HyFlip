package com.hyflip.server.flipper.forge;

import com.hyflip.server.engine.Calibration;
import com.hyflip.server.engine.ForgeSpeed;
import com.hyflip.server.flipper.FlipList;
import com.hyflip.server.profile.CollectionCatalog;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import com.hyflip.server.profile.Unlocks;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuItem;
import com.hyflip.server.source.neu.NeuRecipe;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.ForgeCalibrationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Forge speed model: known modifiers from the player context plus the fit of the calibration
 * wizard's samples. See {@code docs/forge.md}.
 */
@Service
public class ForgeCalibrationService {

    static final NeuData EMPTY_NEU = new NeuData("", Instant.EPOCH, Map.of(), List.of(), Map.of());
    /** Base durations the wizard looks for: short, medium, long. */
    static final long[] SUGGESTION_TARGETS = {3_600, 6 * 3_600, 24 * 3_600};

    private final ForgeCalibrationRepository samples;
    private final NeuRepository neu;
    private final PlayerContextService contexts;
    private final Clock clock;

    @Autowired
    public ForgeCalibrationService(ForgeCalibrationRepository samples, NeuRepository neu,
                                   PlayerContextService contexts) {
        this(samples, neu, contexts, Clock.systemUTC());
    }

    ForgeCalibrationService(ForgeCalibrationRepository samples, NeuRepository neu, PlayerContextService contexts,
                            Clock clock) {
        this.samples = samples;
        this.neu = neu;
        this.contexts = contexts;
        this.clock = clock;
    }

    /** @param calculatedSeconds duration from known modifiers, before calibration */
    public record Suggestion(String recipeId, String name, long baseSeconds, long calculatedSeconds) {
    }

    /** One sample with the derived numbers the wizard shows. */
    public record SampleView(long id, String recipeId, String name, long baseSeconds, double knownReduction,
                             long calculatedSeconds, long observedSeconds, double ratio, long recordedAt) {
    }

    public record Overview(FlipList.ForgeSpeedInfo speed, Calibration.Fit fit, List<SampleView> samples,
                           List<Suggestion> suggestions) {
    }

    public ForgeSpeed speed(PlayerContext ctx) {
        return new ForgeSpeed(ctx.quickForgeReduction() == null ? 0 : ctx.quickForgeReduction(), ctx.moltenForge(),
                fit());
    }

    public Calibration.Fit fit() {
        return Calibration.fit(samples.all().stream()
                .map(s -> new Calibration.Sample(s.baseSeconds(), s.knownReduction(), s.observedSeconds()))
                .toList());
    }

    public Overview overview() {
        PlayerContext ctx = contexts.current();
        ForgeSpeed speed = speed(ctx);
        NeuData data = neu.current().orElse(EMPTY_NEU);
        List<SampleView> views = samples.all().stream().map(s -> {
            Calibration.Sample c = new Calibration.Sample(s.baseSeconds(), s.knownReduction(), s.observedSeconds());
            return new SampleView(s.id(), s.recipeId(), ForgeFlipper.name(data, s.recipeId()), s.baseSeconds(),
                    s.knownReduction(), Math.round(c.calculatedSeconds()), s.observedSeconds(),
                    s.observedSeconds() / c.calculatedSeconds(), s.recordedAt());
        }).toList();
        return new Overview(info(speed, ctx), speed.calibration(), views,
                suggestions(data, ctx, contexts.collections(), speed));
    }

    /** Records how long a recipe really took. The known reduction in effect now is stored with it. */
    public SampleView add(String recipeId, long observedSeconds) {
        if (observedSeconds <= 0) {
            throw ForgeException.invalid("observedSeconds must be positive");
        }
        NeuData data = neu.current().orElse(EMPTY_NEU);
        NeuRecipe recipe = forgeRecipe(data, recipeId)
                .orElseThrow(() -> ForgeException.notFound("No forge recipe for " + recipeId));
        PlayerContext ctx = contexts.current();
        double known = speed(ctx).knownReduction();
        long base = recipe.durationSeconds() == null ? 0 : recipe.durationSeconds();
        if (base <= 0) {
            throw ForgeException.invalid(recipeId + " has no base duration");
        }
        long now = clock.millis();
        long id = samples.insert(recipe.output(), base, known, observedSeconds, now);
        Calibration.Sample c = new Calibration.Sample(base, known, observedSeconds);
        return new SampleView(id, recipe.output(), ForgeFlipper.name(data, recipe.output()), base, known,
                Math.round(c.calculatedSeconds()), observedSeconds, observedSeconds / c.calculatedSeconds(), now);
    }

    public boolean delete(long id) {
        return samples.delete(id);
    }

    public int clear() {
        return samples.deleteAll();
    }

    static List<Suggestion> suggestions(NeuData data, PlayerContext ctx, CollectionCatalog catalog, ForgeSpeed speed) {
        List<NeuRecipe> candidates = data.recipes().stream()
                .filter(r -> r.type() == NeuRecipe.Type.FORGE && r.durationSeconds() != null && r.durationSeconds() > 0)
                .filter(r -> {
                    NeuItem item = data.items().get(r.output());
                    List<NeuItem.Requirement> req = item == null ? List.of() : item.requirements();
                    return Unlocks.check(req, ctx, catalog).state() != Unlocks.State.LOCKED;
                })
                .toList();
        List<Suggestion> out = new ArrayList<>();
        for (long target : SUGGESTION_TARGETS) {
            candidates.stream()
                    .filter(r -> out.stream().noneMatch(s -> s.baseSeconds() == r.durationSeconds()))
                    .min(Comparator.comparingDouble((NeuRecipe r) -> Math.abs(Math.log((double) r.durationSeconds() / target)))
                            .thenComparing(NeuRecipe::output))
                    .ifPresent(r -> out.add(new Suggestion(r.output(), ForgeFlipper.name(data, r.output()),
                            r.durationSeconds(), speed.calculatedSeconds(r.durationSeconds()))));
        }
        return List.copyOf(out);
    }

    /** Forge speed in use for this context. */
    public FlipList.ForgeSpeedInfo info(PlayerContext ctx) {
        return info(speed(ctx), ctx);
    }

    static FlipList.ForgeSpeedInfo info(ForgeSpeed speed, PlayerContext ctx) {
        Calibration.Fit fit = speed.calibration();
        return new FlipList.ForgeSpeedInfo(speed.knownReduction(), ctx.quickForgeReduction(), speed.moltenForge(),
                fit.kind().name(), fit.factor(), fit.fixedSeconds(), fit.provisional());
    }

    static Optional<NeuRecipe> forgeRecipe(NeuData data, String outputId) {
        return data.recipes().stream()
                .filter(r -> r.type() == NeuRecipe.Type.FORGE && r.output().equals(outputId))
                .findFirst();
    }
}
