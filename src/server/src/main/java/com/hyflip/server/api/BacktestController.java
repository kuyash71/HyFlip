package com.hyflip.server.api;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.backtest.Backtester;
import com.hyflip.server.flipper.FlipType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Replays a flipper over the stored history. */
@RestController
@RequestMapping("/api/v1/backtest")
public class BacktestController {

    private final Backtester backtester;

    public BacktestController(Backtester backtester) {
        this.backtester = backtester;
    }

    /**
     * @param from    first moment (epoch ms); default 24 h before {@code to}
     * @param to      last moment; default now minus the horizon
     * @param step    time between moments, default 1h
     * @param horizon time until the sale, default 1h (forge sells after each job's duration)
     */
    @GetMapping("/{type}")
    public Backtester.Result run(@PathVariable String type, @RequestParam(required = false) Long from,
                                 @RequestParam(required = false) Long to,
                                 @RequestParam(defaultValue = "1h") String step,
                                 @RequestParam(defaultValue = "1h") String horizon,
                                 @RequestParam(defaultValue = "20") int top,
                                 @RequestParam(required = false) List<String> hideAlerts) {
        FlipType t = FlipType.fromPath(type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown flipper " + type));
        Duration s = positive(step, "step");
        Duration h = positive(horizon, "horizon");
        if (s.compareTo(Duration.ofMinutes(5)) < 0) {
            throw bad("step must be at least 5 minutes");
        }
        if (top < 1 || top > Backtester.MAX_TOP) {
            throw bad("top must be between 1 and " + Backtester.MAX_TOP);
        }
        long end = to != null ? to : System.currentTimeMillis() - (t == FlipType.FORGE ? 0 : h.toMillis());
        long start = from != null ? from : end - Duration.ofDays(1).toMillis();
        if (start > end) {
            throw bad("from must not be after to");
        }
        Set<AlertType> hide = hideAlerts == null ? Set.of(AlertType.FEW_SALES) : FlipController.alertTypes(hideAlerts);
        return backtester.run(new Backtester.Request(t, start, end, s, h, top, hide));
    }

    private static Duration positive(String value, String name) {
        Duration d = FlipController.duration(value);
        if (d == null) {
            throw bad(name + " is required");
        }
        return d;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
