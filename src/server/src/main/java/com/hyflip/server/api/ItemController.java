package com.hyflip.server.api;

import com.hyflip.server.flipper.RecipeBook;
import com.hyflip.server.flipper.RecipeBooks;
import com.hyflip.server.market.ItemHistory;
import com.hyflip.server.source.neu.NeuItem;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** One item: current prices and price history. */
@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemHistory items;
    private final RecipeBooks books;

    public ItemController(ItemHistory items, RecipeBooks books) {
        this.items = items;
        this.books = books;
    }

    /**
     * Every NEU item with its display name and rarity, for item slots and search.
     *
     * @param commit NEU commit the catalogue comes from, so a client can keep it until NEU updates
     * @param items  ID to {@code [name, rarity]}; rarity is null when the item has none
     */
    public record Catalog(String commit, Map<String, String[]> items) {
    }

    @GetMapping("/catalog")
    public Catalog catalog() {
        RecipeBook book = books.current();
        Map<String, String[]> out = new TreeMap<>();
        for (NeuItem item : book.data().items().values()) {
            out.put(item.internalName(), new String[] {item.displayName(), item.rarity()});
        }
        return new Catalog(book.data().commit(), out);
    }

    @GetMapping("/{id}")
    public ItemHistory.Info info(@PathVariable String id) {
        return items.info(id);
    }

    /**
     * @param from   epoch ms, default 7 days before {@code to}
     * @param to     epoch ms, default now
     * @param bucket bucket size such as {@code 1h}; default fits about 400 points
     */
    @GetMapping("/{id}/history")
    public ItemHistory.History history(@PathVariable String id, @RequestParam(required = false) Long from,
                                       @RequestParam(required = false) Long to,
                                       @RequestParam(required = false) String bucket) {
        Duration b = bucket == null || bucket.isBlank() ? null : FlipController.duration(bucket);
        try {
            return items.history(id, from, to, b);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
