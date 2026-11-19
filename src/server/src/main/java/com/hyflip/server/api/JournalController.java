package com.hyflip.server.api;

import com.hyflip.server.flipper.FlipType;
import com.hyflip.server.journal.TradeJournal;
import java.util.List;
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

/** Trade journal: recorded flips and how their results compare with the estimates. */
@RestController
@RequestMapping("/api/v1/trades")
public class JournalController {

    private final TradeJournal journal;

    public JournalController(TradeJournal journal) {
        this.journal = journal;
    }

    @GetMapping
    public List<TradeJournal.Trade> recent(@RequestParam(required = false) String type,
                                           @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 1000");
        }
        FlipType t = type == null || type.isBlank() ? null : FlipType.fromPath(type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown type " + type));
        return journal.recent(t, limit);
    }

    @GetMapping("/summary")
    public TradeJournal.Summary summary() {
        return journal.summary();
    }

    @PostMapping
    public ResponseEntity<TradeJournal.Trade> record(@RequestBody TradeJournal.RecordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(journal.record(request));
    }

    @PutMapping("/{id}/result")
    public TradeJournal.Trade result(@PathVariable long id, @RequestBody TradeJournal.ResultRequest request) {
        return journal.result(id, request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No trade " + id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return journal.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
