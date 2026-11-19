package com.hyflip.server.api;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.flipper.forge.ForgeCalibrationService;
import com.hyflip.server.flipper.forge.ForgePlanner;
import com.hyflip.server.flipper.forge.ForgeTracker;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Forge Tracker, slot planner and forge speed calibration. */
@RestController
@RequestMapping("/api/v1/forge")
public class ForgeController {

    static final int MAX_HISTORY = 200;

    private final ForgeTracker tracker;
    private final ForgeCalibrationService calibration;
    private final ForgePlanner planner;

    public ForgeController(ForgeTracker tracker, ForgeCalibrationService calibration, ForgePlanner planner) {
        this.tracker = tracker;
        this.calibration = calibration;
        this.planner = planner;
    }

    public record SampleRequest(String recipeId, Long observedSeconds) {
    }

    /**
     * What to start in the free slots. {@code slots} plans that many slots instead of the free
     * ones; {@code hideAlerts} defaults to FEW_SALES as in the list.
     */
    @GetMapping("/plan")
    public ForgePlanner.Plan plan(@RequestParam(required = false) Double budget,
                                  @RequestParam(required = false) String nextSessionIn,
                                  @RequestParam(required = false) Integer slots,
                                  @RequestParam(required = false) List<String> hideAlerts) {
        if (budget != null && budget < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "budget must not be negative");
        }
        if (slots != null && (slots < 1 || slots > 7)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slots must be between 1 and 7");
        }
        return planner.plan(budget, FlipController.duration(nextSessionIn), slots,
                hideAlerts == null ? Set.of(AlertType.FEW_SALES) : FlipController.alertTypes(hideAlerts));
    }

    @GetMapping("/slots")
    public ForgeTracker.Slots slots() {
        return tracker.slots();
    }

    @GetMapping("/jobs")
    public java.util.List<ForgeTracker.JobView> jobs(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_HISTORY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_HISTORY);
        }
        return tracker.history(limit);
    }

    @PostMapping("/jobs")
    public ResponseEntity<ForgeTracker.JobView> start(@RequestBody ForgeTracker.StartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tracker.start(request));
    }

    @PostMapping("/jobs/{id}/collect")
    public ForgeTracker.JobView collect(@PathVariable long id) {
        return tracker.collect(id).orElseThrow(() -> notFound("job", id));
    }

    public record SaleRequest(Double value) {
    }

    /** What the player actually received for the output; {@code {"value": null}} clears it. */
    @PutMapping("/jobs/{id}/sale")
    public ForgeTracker.JobView recordSale(@PathVariable long id, @RequestBody SaleRequest request) {
        return tracker.recordSale(id, request.value()).orElseThrow(() -> notFound("job", id));
    }

    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable long id) {
        if (!tracker.delete(id)) {
            throw notFound("job", id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/calibration")
    public ForgeCalibrationService.Overview calibration() {
        return calibration.overview();
    }

    @PostMapping("/calibration")
    public ResponseEntity<ForgeCalibrationService.SampleView> addSample(@RequestBody SampleRequest request) {
        if (request.recipeId() == null || request.observedSeconds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recipeId and observedSeconds are required");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calibration.add(request.recipeId(), request.observedSeconds()));
    }

    @DeleteMapping("/calibration/{id}")
    public ResponseEntity<Void> deleteSample(@PathVariable long id) {
        if (!calibration.delete(id)) {
            throw notFound("sample", id);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/calibration")
    public ResponseEntity<Void> clearSamples() {
        calibration.clear();
        return ResponseEntity.noContent().build();
    }

    private static ResponseStatusException notFound(String what, long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + what + " " + id);
    }
}
