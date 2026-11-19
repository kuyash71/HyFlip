import { describe, expect, it } from "vitest";
import {
  parseIsoDuration,
  formatAgo,
  formatBytes,
  formatClock,
  formatCoins,
  formatCountdown,
  formatDuration,
  formatInterval,
  formatPercent,
  formatQuantity,
  formatRuns,
  parseCoins,
  parseDuration,
} from "./format";

describe("format", () => {
  it("formats clock times in a zone", () => {
    // 2026-11-18 12:00 UTC = 15:00 in Istanbul.
    const now = Date.UTC(2026, 10, 18, 12, 0);
    expect(formatClock(Date.UTC(2026, 10, 18, 15, 40), "Europe/Istanbul", now)).toBe("18:40");
    expect(formatClock(Date.UTC(2026, 10, 18, 22, 10), "Europe/Istanbul", now)).toBe("01:10 tomorrow");
    expect(formatClock(Date.UTC(2026, 10, 18, 22, 10), "UTC", now)).toBe("22:10");
    expect(formatClock(Date.UTC(2026, 10, 20, 9, 0), "UTC", now)).toBe("09:00 Fri 20 Nov");
    expect(formatClock(Date.UTC(2026, 10, 18, 13, 5), "Not/AZone", now)).toMatch(/^\d\d:05/);
  });

  it("formats bytes", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(3 * 1024 ** 3)).toBe("3.0 GB");
  });

  it("formats relative times", () => {
    const now = 1_000_000_000;
    expect(formatAgo(null, now)).toBe("never");
    expect(formatAgo(now - 12_000, now)).toBe("12 s ago");
    expect(formatAgo(now - 5 * 60_000, now)).toBe("5 min ago");
    expect(formatAgo(now - 3 * 3_600_000, now)).toBe("3 h ago");
  });

  it("formats intervals", () => {
    expect(formatInterval(60)).toBe("60 s");
    expect(formatInterval(1800)).toBe("30 min");
    expect(formatInterval(86400)).toBe("24 h");
  });
});

describe("flip formats", () => {
  it("formats coins", () => {
    expect(formatCoins(950)).toBe("950");
    expect(formatCoins(12_345)).toBe("12.3k");
    expect(formatCoins(4_560_000)).toBe("4.56M");
    expect(formatCoins(-627_655_000)).toBe("-628M");
    expect(formatCoins(2_332_000_000)).toBe("2.33B");
  });

  it("formats durations and countdowns", () => {
    expect(formatDuration(24)).toBe("24 s");
    expect(formatDuration(17_280)).toBe("4 h 48 min");
    expect(formatDuration(7 * 86400)).toBe("7 d");
    expect(formatCountdown(3723)).toBe("1:02:03");
    expect(formatCountdown(90_061)).toBe("1d 1:01:01");
    expect(formatCountdown(-5)).toBe("0:00:00");
  });

  it("formats percentages", () => {
    expect(formatPercent(0.199)).toBe("19.9%");
    expect(formatPercent(2.5)).toBe("250%");
    expect(formatPercent(null)).toBe("—");
  });

  it("parses typed durations", () => {
    expect(parseDuration("4h 30m")).toBe(16_200);
    expect(parseDuration("1d2h")).toBe(93_600);
    expect(parseDuration("45min")).toBe(2_700);
    expect(parseDuration("90s")).toBe(90);
    expect(parseDuration("1:30:00")).toBe(5_400);
    expect(parseDuration("4:30")).toBe(16_200);
    expect(parseDuration("30")).toBe(1_800);
    expect(parseDuration("soon")).toBeNull();
    expect(parseDuration("4h soon")).toBeNull();
    expect(parseDuration("")).toBeNull();
  });
});

describe("parseCoins", () => {
  it("reads k, m and b suffixes", () => {
    expect(parseCoins("50m")).toBe(50_000_000);
    expect(parseCoins("1.5B")).toBe(1_500_000_000);
    expect(parseCoins(" 200k ")).toBe(200_000);
    expect(parseCoins("1000")).toBe(1000);
    expect(parseCoins("")).toBeNull();
    expect(parseCoins("lots")).toBeNull();
  });

  it("allows thousands separators", () => {
    expect(parseCoins("1,200,000")).toBe(1_200_000);
    expect(parseCoins("1 200 000")).toBe(1_200_000);
    expect(parseCoins("1,2,")).toBe(12);
  });
});

describe("formatQuantity", () => {
  it("keeps whole numbers and rounds fractions", () => {
    expect(formatQuantity(25600)).toBe("25,600");
    expect(formatQuantity(0.5)).toBe("0.5");
    expect(formatQuantity(1 / 3)).toBe("0.33");
  });
});

describe("formatRuns", () => {
  it("keeps a decimal for fewer than 10 runs", () => {
    expect(formatRuns(0.84)).toBe("0.8");
    expect(formatRuns(707462.5)).toBe("707,462");
  });
});

describe("parseIsoDuration", () => {
  it("reads what the server sends", () => {
    expect(parseIsoDuration("PT8H")).toBe(28800);
    expect(parseIsoDuration("PT1H30M")).toBe(5400);
    expect(parseIsoDuration("P1DT2H")).toBe(93600);
    expect(parseIsoDuration("PT28800S")).toBe(28800);
    expect(parseIsoDuration("8h")).toBeNull();
    expect(parseIsoDuration("PT")).toBeNull();
  });
});
