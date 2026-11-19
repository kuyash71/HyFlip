package com.hyflip.server.flipper;

import java.util.Locale;

/** Short human formats for guide text. The UI formats numbers itself. */
public final class Coins {

    private Coins() {
    }

    /** 950, 12.3k, 4.56M, 1.2B. */
    public static String format(double coins) {
        double abs = Math.abs(coins);
        String sign = coins < 0 ? "-" : "";
        if (abs >= 1e9) {
            return sign + trim(abs / 1e9) + "B";
        }
        if (abs >= 1e6) {
            return sign + trim(abs / 1e6) + "M";
        }
        if (abs >= 1e3) {
            return sign + trim(abs / 1e3) + "k";
        }
        return sign + Math.round(abs);
    }

    /** A unit price: like {@link #format} but with one decimal below 1,000 (12.3, 950.1), as the Bazaar shows. */
    public static String price(double coins) {
        if (Math.abs(coins) >= 1e3) {
            return format(coins);
        }
        double rounded = Math.round(coins * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** 45 s, 30 min, 4 h 30 min, 3 d 2 h. */
    public static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        if (hours < 48) {
            return restMinutes == 0 ? hours + " h" : hours + " h " + restMinutes + " min";
        }
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours == 0 ? days + " d" : days + " d " + restHours + " h";
    }

    public static String amount(double quantity) {
        return quantity == Math.rint(quantity) ? String.format(Locale.ROOT, "%,d", (long) quantity) : trim(quantity);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, v >= 100 ? "%.0f" : v >= 10 ? "%.1f" : "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
