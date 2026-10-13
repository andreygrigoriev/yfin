package io.andreygrigoriev.yfin.internal.repair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The numpy/scipy/sklearn functions the repair code relies on, with numpy's NaN semantics. */
final class Stats {

    private Stats() {}

    /** {@code np.mean}: NaN for an empty array, NaN if any value is NaN. */
    static double mean(double[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    /** {@code np.std(v, ddof=ddof)}. */
    static double std(double[] v, int ddof) {
        if (v.length - ddof <= 0) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - ddof));
    }

    /** {@code np.median}: NaN if any value is NaN or the array is empty. */
    static double median(double[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        for (double x : v) {
            if (Double.isNaN(x)) {
                return Double.NaN;
            }
        }
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n & 1) == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** {@code np.percentile(v, q)} with linear interpolation; NaN if any value is NaN. */
    static double percentile(double[] v, double q) {
        if (v.length == 0) {
            return Double.NaN;
        }
        for (double x : v) {
            if (Double.isNaN(x)) {
                return Double.NaN;
            }
        }
        double[] s = v.clone();
        Arrays.sort(s);
        double pos = (s.length - 1) * q / 100.0;
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, s.length - 1);
        double frac = pos - lo;
        // numpy's _lerp: a + (b - a) * t, switching formulation for t >= 0.5
        double a = s[lo];
        double b = s[hi];
        double diff = b - a;
        return frac >= 0.5 ? b - diff * (1 - frac) : a + diff * frac;
    }

    /** {@code np.average(v, weights=w)}. */
    static double weightedAverage(double[] v, double[] w) {
        double s = 0;
        double ws = 0;
        for (int i = 0; i < v.length; i++) {
            s += v[i] * w[i];
            ws += w[i];
        }
        return s / ws;
    }

    /** {@code np.round(x, decimals)}: round half to even at the given number of decimals. */
    static double round(double x, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.rint(x * f) / f;
    }

    /** Python's {@code math.isclose(a, b, rel_tol=relTol)}. */
    static boolean isClose(double a, double b, double relTol) {
        if (a == b) {
            return true;
        }
        if (Double.isInfinite(a) || Double.isInfinite(b)) {
            return false;
        }
        double diff = Math.abs(b - a);
        return diff <= Math.abs(relTol * b) || diff <= Math.abs(relTol * a);
    }

    /**
     * sklearn {@code DBSCAN(eps, min_samples).fit_predict} on one-dimensional points: labels per
     * point, {@code -1} for noise, clusters numbered in the order sklearn finds them (by first core
     * point in input order).
     */
    static int[] dbscan1d(double[] x, double eps, int minSamples) {
        int n = x.length;
        List<List<Integer>> neighbours = new ArrayList<>(n);
        boolean[] core = new boolean[n];
        for (int i = 0; i < n; i++) {
            var nb = new ArrayList<Integer>();
            for (int j = 0; j < n; j++) {
                if (Math.abs(x[i] - x[j]) <= eps) {
                    nb.add(j);
                }
            }
            neighbours.add(nb);
            core[i] = nb.size() >= minSamples;
        }
        int[] labels = new int[n];
        Arrays.fill(labels, -1);
        int label = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] != -1 || !core[i]) {
                continue;
            }
            var stack = new ArrayList<Integer>();
            stack.add(i);
            labels[i] = label;
            while (!stack.isEmpty()) {
                int p = stack.removeLast();
                if (!core[p]) {
                    continue;
                }
                for (int q : neighbours.get(p)) {
                    if (labels[q] == -1) {
                        labels[q] = label;
                        stack.add(q);
                    }
                }
            }
            label++;
        }
        return labels;
    }

    static double nanMax(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b);
    }

    static double nanMin(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
    }
}
