package io.github.andreygrigoriev.yfin.model;

import java.util.List;

/**
 * Corporate actions of a symbol, each list sorted by time.
 *
 * @param capitalGains empty unless the symbol is a fund or ETF
 */
public record Actions(List<Dividend> dividends, List<Split> splits, List<CapitalGain> capitalGains) {

    public Actions {
        dividends = List.copyOf(dividends);
        splits = List.copyOf(splits);
        capitalGains = List.copyOf(capitalGains);
    }

    /** Whether there are no actions at all. */
    public boolean isEmpty() {
        return dividends.isEmpty() && splits.isEmpty() && capitalGains.isEmpty();
    }
}
