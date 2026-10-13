package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.SPLITS;

import io.andreygrigoriev.yfin.request.Interval;

/**
 * yfinance's {@code _fix_bad_stock_splits}: Yahoo sometimes fails to apply a split to older prices
 * (or applies it twice). For every split, the bars up to a few rows after it are checked for a
 * sudden change of the split ratio, and volume and dividends are corrected with the prices.
 */
final class SplitRepair {

    private SplitRepair() {}

    static Frame fix(Frame input, Interval interval, RepairContext ctx) {
        if (input.isEmpty() || !Intervals.interday(interval)) {
            return input;
        }
        Frame df = input.inZone(ctx.zone()).sortedAscending();
        int[] splitRows = Frame.where(SuddenChange.nonZero(df.col(SPLITS)));
        if (splitRows.length == 0) {
            return df;
        }
        for (int splitIdx : splitRows) {
            double split = df.get(SPLITS, splitIdx);
            if (splitIdx == 0) {
                continue;
            }
            // add on a week to detect the big change
            int idx = splitIdx + (Intervals.multiday(interval) ? 1 : 5);
            int n = df.size();
            int cutoff = Math.min(n, idx);
            Frame preSplit = df.slice(0, cutoff + 1);
            Frame repaired = SuddenChange.fix(preSplit, interval, split, false, true, true, ctx)
                    .sortedAscending();
            if (cutoff == n - 1) {
                df = repaired;
            } else {
                Frame post = df.slice(cutoff + 1, n);
                df = post.isEmpty() ? repaired : Frame.concat(repaired, post);
            }
        }
        return df;
    }
}
