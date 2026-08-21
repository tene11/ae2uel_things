package com.example.ae2uelthings.api;


public final class DiskCapacityFormat {

    private static final String[] SUFFIXES = {"", "k", "M", "G", "T", "P"};

    private static final String[] FLUID_SUFFIXES = {"B", "kB", "MB", "GB", "TB", "PB"};

    private DiskCapacityFormat() {
    }

    public static String format(long value) {
        if (value < 0) {
            return "-" + format(-value);
        }
        if (value < 1000) {
            return Long.toString(value);
        }

        double scaled = value;
        int suffixIndex = 0;
        while (scaled >= 1000 && suffixIndex < SUFFIXES.length - 1) {
            scaled /= 1000.0;
            suffixIndex++;
        }
        return formatNumber(scaled) + SUFFIXES[suffixIndex];
    }

    public static String formatFluidMb(long mb) {
        if (mb < 0) {
            return "-" + formatFluidMb(-mb);
        }
        if (mb < 1000) {
            return mb + "mB";
        }

        double buckets = mb / 1000.0;
        int suffixIndex = 0;
        while (buckets >= 1000 && suffixIndex < FLUID_SUFFIXES.length - 1) {
            buckets /= 1000.0;
            suffixIndex++;
        }
        return formatNumber(buckets) + FLUID_SUFFIXES[suffixIndex];
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value)) {
            return Long.toString((long) value);
        }
        String s = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}