package io.andreygrigoriev.yfin.internal.repair;

import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.ADJ_CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CAPITAL_GAINS;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.CLOSE;
import static io.andreygrigoriev.yfin.internal.repair.Frame.Col.DIVIDENDS;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yfinance's {@code _repair_capital_gains}: Yahoo sometimes includes capital gains in the dividend
 * as well, counting them twice in the adjusted close. Detected from the price drop on distribution
 * days; when two thirds or more of the distributions look double-counted, the dividends are reduced
 * by the capital gains and the adjustment is corrected.
 */
final class CapitalGainsRepair {

    private static final Logger LOG = LoggerFactory.getLogger(CapitalGainsRepair.class);

    private CapitalGainsRepair() {}

    static Frame fix(Frame input, RepairContext ctx) {
        if (!input.hasCapitalGains()) {
            return input;
        }
        boolean anyGain = false;
        for (double g : input.col(CAPITAL_GAINS)) {
            anyGain |= g != 0;
        }
        if (!anyGain) {
            return input;
        }
        Frame df = input.sortedAscending();
        int n = df.size();
        double[] c = df.col(CLOSE).clone();
        double[] ac = df.col(ADJ_CLOSE).clone();
        double[] div = df.col(DIVIDENDS);
        double[] cg = df.col(CAPITAL_GAINS);

        // normal volatility: mean absolute daily change without distributions
        double sum = 0;
        int count = 0;
        for (int i = 1; i < n; i++) {
            double pct = Math.abs(c[i] / c[i - 1] - 1);
            if (div[i] == 0 && cg[i] == 0 && !Double.isNaN(pct)) {
                sum += pct;
                count++;
            }
        }
        double meanDrop = count == 0 ? Double.NaN : sum / count;

        double[] adj = new double[n];
        for (int i = 0; i < n; i++) {
            adj[i] = ac[i] / c[i];
        }
        Map<Integer, Boolean> doubleCounted = new LinkedHashMap<>();
        for (int i = 1; i < n; i++) {
            if (!(cg[i] > 0) || div[i] < cg[i]) {
                continue; // a dividend smaller than the gains cannot include them
            }
            double divPct = div[i] / c[i - 1];
            double cgPct = cg[i] / c[i - 1];
            double dropExclVol = (c[i - 1] - c[i]) / c[i - 1] - meanDrop;
            doubleCounted.put(i, Math.abs(dropExclVol - divPct) < Math.abs(dropExclVol - (divPct + cgPct)));
        }
        if (doubleCounted.isEmpty()) {
            return df;
        }
        long nDouble = doubleCounted.values().stream().filter(b -> b).count();
        if ((double) nDouble / doubleCounted.size() >= 0.666) {
            for (int idx : doubleCounted.keySet()) {
                double trueDividend = div[idx] - cg[idx];
                div[idx] = trueDividend;
                double adjBefore = (ac[idx - 1] / c[idx - 1]) / (ac[idx] / c[idx]);
                double adjCorrect = 1.0 - (trueDividend + cg[idx]) / c[idx - 1];
                double correction = adjCorrect / adjBefore;
                Instant until = df.time(idx).toInstant().minus(1, ChronoUnit.DAYS);
                Instant upTo = df.time(idx).toInstant();
                for (int i = 0; i < n; i++) {
                    Instant t = df.time(i).toInstant();
                    if (!t.isAfter(until)) {
                        adj[i] *= correction;
                    }
                    if (!t.isAfter(upTo)) {
                        df.repaired()[i] = true;
                    }
                }
                LOG.info(
                        "{}: repaired capital-gains double-count at {}, adj correction = {}",
                        ctx.symbol(),
                        df.time(idx).toLocalDate(),
                        correction);
            }
        }
        for (int i = 0; i < n; i++) {
            df.set(ADJ_CLOSE, i, c[i] * adj[i]);
        }
        return df;
    }
}
