package io.andreygrigoriev.yfin.internal.endpoint.quotesummary;

import java.util.Optional;

/** The {@code quoteSummary} modules yfinance accepts ({@code quote_summary_valid_modules}). */
public enum QuoteSummaryModule {
    SUMMARY_PROFILE("summaryProfile"),
    SUMMARY_DETAIL("summaryDetail"),
    ASSET_PROFILE("assetProfile"),
    FUND_PROFILE("fundProfile"),
    PRICE("price"),
    QUOTE_TYPE("quoteType"),
    ESG_SCORES("esgScores"),
    INCOME_STATEMENT_HISTORY("incomeStatementHistory"),
    INCOME_STATEMENT_HISTORY_QUARTERLY("incomeStatementHistoryQuarterly"),
    BALANCE_SHEET_HISTORY("balanceSheetHistory"),
    BALANCE_SHEET_HISTORY_QUARTERLY("balanceSheetHistoryQuarterly"),
    CASH_FLOW_STATEMENT_HISTORY("cashFlowStatementHistory"),
    CASH_FLOW_STATEMENT_HISTORY_QUARTERLY("cashFlowStatementHistoryQuarterly"),
    DEFAULT_KEY_STATISTICS("defaultKeyStatistics"),
    FINANCIAL_DATA("financialData"),
    CALENDAR_EVENTS("calendarEvents"),
    SEC_FILINGS("secFilings"),
    UPGRADE_DOWNGRADE_HISTORY("upgradeDowngradeHistory"),
    INSTITUTION_OWNERSHIP("institutionOwnership"),
    FUND_OWNERSHIP("fundOwnership"),
    MAJOR_DIRECT_HOLDERS("majorDirectHolders"),
    MAJOR_HOLDERS_BREAKDOWN("majorHoldersBreakdown"),
    INSIDER_TRANSACTIONS("insiderTransactions"),
    INSIDER_HOLDERS("insiderHolders"),
    NET_SHARE_PURCHASE_ACTIVITY("netSharePurchaseActivity"),
    EARNINGS("earnings"),
    EARNINGS_HISTORY("earningsHistory"),
    EARNINGS_TREND("earningsTrend"),
    INDUSTRY_TREND("industryTrend"),
    INDEX_TREND("indexTrend"),
    SECTOR_TREND("sectorTrend"),
    RECOMMENDATION_TREND("recommendationTrend"),
    FUTURES_CHAIN("futuresChain");

    private final String yahooName;

    QuoteSummaryModule(String yahooName) {
        this.yahooName = yahooName;
    }

    /** The module name in Yahoo requests and responses. */
    public String yahooName() {
        return yahooName;
    }

    /** The module with Yahoo name {@code name}, if any. */
    public static Optional<QuoteSummaryModule> fromYahooName(String name) {
        for (QuoteSummaryModule m : values()) {
            if (m.yahooName.equals(name)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
