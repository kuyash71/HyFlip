package com.hyflip.server.flipper;

import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuRepository;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** The {@link RecipeBook} of the loaded NEU data, rebuilt when NEU updates. */
@Service
public class RecipeBooks {

    private final NeuRepository neu;
    private final AtomicReference<RecipeBook> cached = new AtomicReference<>(RecipeBook.EMPTY);

    public RecipeBooks(NeuRepository neu) {
        this.neu = neu;
    }

    public RecipeBook current() {
        NeuData data = neu.current().orElse(null);
        if (data == null) {
            return RecipeBook.EMPTY;
        }
        RecipeBook book = cached.get();
        if (book.data() != data) {
            book = RecipeBook.of(data);
            cached.set(book);
        }
        return book;
    }
}
