package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CAPITAL_GAINS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.DIVIDENDS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.HIGH;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.LOW;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.OPEN;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.SPLITS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.VOLUME;

import io.andreygrigoriev.yfin.internal.repair.Frame.Col;
import io.andreygrigoriev.yfin.request.Interval;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * yfinance's {@code _resample}: aggregates bars into calendar bins (days, Monday weeks, months,
 * quarters) the way pandas {@code resample(..., label='left', closed='left')} does, including empty
 * bins, whose prices are filled with the previous close.
 */
final class Resample {

    private Resample() {}

    /**
     * @param target {@code 1d}, {@code 1wk}, {@code 1mo} or {@code 3mo}
     * @param ytd the request period was year-to-date (weeks and quarters then align to 1 January)
     * @param today the current date in the exchange zone, for year-to-date and quarter alignment
     */
    static Frame resample(Frame df, Interval target, ZoneId zone, boolean ytd, LocalDate today) {
        if (df.isEmpty()) {
            return df;
        }
        Frame src = df.inZone(zone).sortedAscending();
        LocalDate first = src.time(0).toLocalDate();
        LocalDate last = src.time(src.size() - 1).toLocalDate();
        LocalDate bin = binStart(first, target, ytd, today);
        List<LocalDate> bins = new ArrayList<>();
        while (!bin.isAfter(last)) {
            bins.add(bin);
            bin = next(bin, target, ytd);
        }
        int nb = bins.size();
        ZonedDateTime[] times = new ZonedDateTime[nb];
        for (int b = 0; b < nb; b++) {
            times[b] = bins.get(b).atStartOfDay(zone);
        }
        Frame out = new Frame(times, src.hasCapitalGains());
        for (Col c : new Col[] {OPEN, HIGH, LOW, CLOSE, ADJ_CLOSE}) {
            java.util.Arrays.fill(out.col(c), Double.NaN);
        }
        java.util.Arrays.fill(out.col(SPLITS), 1.0);
        int b = 0;
        for (int i = 0; i < src.size(); i++) {
            LocalDate d = src.time(i).toLocalDate();
            while (b + 1 < nb && !d.isBefore(bins.get(b + 1))) {
                b++;
            }
            if (Double.isNaN(out.get(OPEN, b))) {
                out.set(OPEN, b, src.get(OPEN, i));
            }
            out.set(HIGH, b, Stats.nanMax(out.get(HIGH, b), src.get(HIGH, i)));
            out.set(LOW, b, Stats.nanMin(out.get(LOW, b), src.get(LOW, i)));
            if (!Double.isNaN(src.get(CLOSE, i))) {
                out.set(CLOSE, b, src.get(CLOSE, i));
            }
            if (!Double.isNaN(src.get(ADJ_CLOSE, i))) {
                out.set(ADJ_CLOSE, b, src.get(ADJ_CLOSE, i));
            }
            out.set(VOLUME, b, out.get(VOLUME, b) + nanToZero(src.get(VOLUME, i)));
            out.set(DIVIDENDS, b, out.get(DIVIDENDS, b) + nanToZero(src.get(DIVIDENDS, i)));
            out.set(CAPITAL_GAINS, b, out.get(CAPITAL_GAINS, b) + nanToZero(src.get(CAPITAL_GAINS, i)));
            double split = src.get(SPLITS, i);
            out.set(SPLITS, b, out.get(SPLITS, b) * (split == 0.0 ? 1.0 : split));
            out.repaired()[b] |= src.repaired()[i];
        }
        double prevClose = Double.NaN;
        for (int i = 0; i < nb; i++) {
            if (out.get(SPLITS, i) == 1.0) {
                out.set(SPLITS, i, 0.0);
            }
            // long holidays: fill empty bins with the previous close
            double close = out.get(CLOSE, i);
            for (Col c : Frame.OHLC) {
                if (Double.isNaN(out.get(c, i))) {
                    out.set(c, i, prevClose);
                }
            }
            if (!Double.isNaN(close)) {
                prevClose = close;
            }
        }
        return out;
    }

    private static LocalDate binStart(LocalDate d, Interval target, boolean ytd, LocalDate today) {
        return switch (target) {
            case ONE_DAY -> d;
            case ONE_WEEK -> {
                if (ytd) {
                    LocalDate origin = today.withDayOfYear(1);
                    long days = ChronoUnit.DAYS.between(origin, d);
                    yield origin.plusDays(Math.floorDiv(days, 7) * 7);
                }
                yield d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            }
            case ONE_MONTH -> d.withDayOfMonth(1);
            case THREE_MONTHS -> {
                int align = ytd ? 1 : today.getMonthValue();
                int offset = Math.floorMod(d.getMonthValue() - align, 3);
                yield d.withDayOfMonth(1).minusMonths(offset);
            }
            default -> throw new IllegalArgumentException("cannot resample to " + target);
        };
    }

    private static LocalDate next(LocalDate bin, Interval target, boolean ytd) {
        return switch (target) {
            case ONE_DAY -> bin.plusDays(1);
            case ONE_WEEK -> bin.plusWeeks(1);
            case ONE_MONTH -> bin.plusMonths(1);
            case THREE_MONTHS -> bin.plusMonths(3);
            default -> throw new IllegalArgumentException("cannot resample to " + target);
        };
    }

    private static double nanToZero(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }
}
