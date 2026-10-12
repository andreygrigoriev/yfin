package io.andreygrigoriev.yfin.request;

/** Reporting period of a financial statement. */
public enum Frequency {
    /** Fiscal years; Yahoo returns up to the last four or five. */
    ANNUAL("annual"),
    /** Fiscal quarters; Yahoo returns up to the last five or six. */
    QUARTERLY("quarterly"),
    /**
     * Trailing twelve months, latest only. Available for the income statement and the cash flow
     * statement, not for the balance sheet.
     */
    TRAILING("trailing");

    private final String yahooPrefix;

    Frequency(String yahooPrefix) {
        this.yahooPrefix = yahooPrefix;
    }

    /** Prefix of Yahoo's timeseries type names, e.g. {@code annual} in {@code annualTotalRevenue}. */
    public String yahooPrefix() {
        return yahooPrefix;
    }
}
