package io.andreygrigoriev.yfin.model;

import java.util.Objects;

/**
 * A financial statement line item, named by its Yahoo key without the frequency prefix (e.g.
 * {@code TotalRevenue}, not {@code annualTotalRevenue}). The constants cover common items; any
 * other Yahoo key works through {@link #of(String)}.
 *
 * @param key Yahoo key, e.g. {@code TotalRevenue}
 */
public record LineItem(String key) {

    // ---- income statement ----
    public static final LineItem TOTAL_REVENUE = of("TotalRevenue");
    public static final LineItem OPERATING_REVENUE = of("OperatingRevenue");
    public static final LineItem COST_OF_REVENUE = of("CostOfRevenue");
    public static final LineItem GROSS_PROFIT = of("GrossProfit");
    public static final LineItem RESEARCH_AND_DEVELOPMENT = of("ResearchAndDevelopment");
    public static final LineItem SELLING_GENERAL_AND_ADMINISTRATION = of("SellingGeneralAndAdministration");
    public static final LineItem OPERATING_EXPENSE = of("OperatingExpense");
    public static final LineItem OPERATING_INCOME = of("OperatingIncome");
    public static final LineItem INTEREST_INCOME = of("InterestIncome");
    public static final LineItem INTEREST_EXPENSE = of("InterestExpense");
    public static final LineItem NET_INTEREST_INCOME = of("NetInterestIncome");
    public static final LineItem PRETAX_INCOME = of("PretaxIncome");
    public static final LineItem TAX_PROVISION = of("TaxProvision");
    public static final LineItem NET_INCOME = of("NetIncome");
    public static final LineItem NET_INCOME_COMMON_STOCKHOLDERS = of("NetIncomeCommonStockholders");
    public static final LineItem EBIT = of("EBIT");
    public static final LineItem EBITDA = of("EBITDA");
    public static final LineItem NORMALIZED_EBITDA = of("NormalizedEBITDA");
    public static final LineItem BASIC_EPS = of("BasicEPS");
    public static final LineItem DILUTED_EPS = of("DilutedEPS");
    public static final LineItem BASIC_AVERAGE_SHARES = of("BasicAverageShares");
    public static final LineItem DILUTED_AVERAGE_SHARES = of("DilutedAverageShares");
    public static final LineItem TOTAL_EXPENSES = of("TotalExpenses");

    // ---- balance sheet ----
    public static final LineItem TOTAL_ASSETS = of("TotalAssets");
    public static final LineItem CURRENT_ASSETS = of("CurrentAssets");
    public static final LineItem CASH_AND_CASH_EQUIVALENTS = of("CashAndCashEquivalents");
    public static final LineItem CASH_CASH_EQUIVALENTS_AND_SHORT_TERM_INVESTMENTS =
            of("CashCashEquivalentsAndShortTermInvestments");
    public static final LineItem ACCOUNTS_RECEIVABLE = of("AccountsReceivable");
    public static final LineItem INVENTORY = of("Inventory");
    public static final LineItem NET_PPE = of("NetPPE");
    public static final LineItem GOODWILL = of("Goodwill");
    public static final LineItem TOTAL_LIABILITIES_NET_MINORITY_INTEREST = of("TotalLiabilitiesNetMinorityInterest");
    public static final LineItem CURRENT_LIABILITIES = of("CurrentLiabilities");
    public static final LineItem ACCOUNTS_PAYABLE = of("AccountsPayable");
    public static final LineItem CURRENT_DEBT = of("CurrentDebt");
    public static final LineItem LONG_TERM_DEBT = of("LongTermDebt");
    public static final LineItem TOTAL_DEBT = of("TotalDebt");
    public static final LineItem NET_DEBT = of("NetDebt");
    public static final LineItem STOCKHOLDERS_EQUITY = of("StockholdersEquity");
    public static final LineItem COMMON_STOCK_EQUITY = of("CommonStockEquity");
    public static final LineItem RETAINED_EARNINGS = of("RetainedEarnings");
    public static final LineItem WORKING_CAPITAL = of("WorkingCapital");
    public static final LineItem TANGIBLE_BOOK_VALUE = of("TangibleBookValue");
    public static final LineItem ORDINARY_SHARES_NUMBER = of("OrdinarySharesNumber");
    public static final LineItem SHARE_ISSUED = of("ShareIssued");

    // ---- cash flow ----
    public static final LineItem OPERATING_CASH_FLOW = of("OperatingCashFlow");
    public static final LineItem INVESTING_CASH_FLOW = of("InvestingCashFlow");
    public static final LineItem FINANCING_CASH_FLOW = of("FinancingCashFlow");
    public static final LineItem FREE_CASH_FLOW = of("FreeCashFlow");
    public static final LineItem CAPITAL_EXPENDITURE = of("CapitalExpenditure");
    public static final LineItem DEPRECIATION_AND_AMORTIZATION = of("DepreciationAndAmortization");
    public static final LineItem STOCK_BASED_COMPENSATION = of("StockBasedCompensation");
    public static final LineItem CHANGE_IN_WORKING_CAPITAL = of("ChangeInWorkingCapital");
    public static final LineItem CASH_DIVIDENDS_PAID = of("CashDividendsPaid");
    public static final LineItem REPURCHASE_OF_CAPITAL_STOCK = of("RepurchaseOfCapitalStock");
    public static final LineItem ISSUANCE_OF_DEBT = of("IssuanceOfDebt");
    public static final LineItem REPAYMENT_OF_DEBT = of("RepaymentOfDebt");
    public static final LineItem CHANGES_IN_CASH = of("ChangesInCash");
    public static final LineItem END_CASH_POSITION = of("EndCashPosition");

    /**
     * @throws IllegalArgumentException when {@code key} is blank
     */
    public LineItem {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("blank line item key");
        }
    }

    /** The line item with Yahoo key {@code key}. */
    public static LineItem of(String key) {
        return new LineItem(key);
    }

    @Override
    public String toString() {
        return key;
    }
}
