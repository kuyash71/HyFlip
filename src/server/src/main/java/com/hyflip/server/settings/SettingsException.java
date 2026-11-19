package com.hyflip.server.settings;

import java.util.List;

/** Rejected settings; the API answers 400 with every problem found. */
public class SettingsException extends RuntimeException {

    private final List<String> problems;

    public SettingsException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public SettingsException(String problem) {
        this(List.of(problem));
    }

    public List<String> problems() {
        return problems;
    }
}
