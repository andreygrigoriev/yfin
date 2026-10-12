package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.yfin.request.EquityQuery;
import io.andreygrigoriev.yfin.request.EtfQuery;
import io.andreygrigoriev.yfin.request.FundQuery;
import io.andreygrigoriev.yfin.request.SortField;
import org.junit.jupiter.api.Test;

class ScreenerTest {

    @Test
    void predefinedTable() {
        assertThat(Screener.predefinedNames())
                .hasSize(19)
                .startsWith("aggressive_small_caps", "day_gainers", "day_losers")
                .contains("most_actives", "top_mutual_funds", "bond_etfs");
        Screener losers = Screener.predefinedQuery("day_losers");
        assertThat(losers.sort()).isEqualTo(SortField.ascending("percentchange"));
        assertThat(losers.query()).isInstanceOf(EquityQuery.class);
        assertThat(Screener.predefinedQuery("high_yield_bond").query()).isInstanceOf(FundQuery.class);
        assertThat(Screener.predefinedQuery("technology_etfs").query()).isInstanceOf(EtfQuery.class);
        for (String name : Screener.predefinedNames()) {
            assertThat(Screener.predefinedQuery(name).sort()).as(name).isNotNull();
        }
        assertThatThrownBy(() -> Screener.predefinedQuery("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown predefined screen: nope");
    }

    @Test
    void options() {
        EquityQuery q = EquityQuery.eq("region", "us");
        Screener s = Screener.of(q);
        assertThat(s.query()).isEqualTo(q);
        assertThat(s.predefinedName()).isNull();
        assertThat(s.offset()).isNull();
        assertThat(s.size()).isNull();
        assertThat(s.sort()).isNull();

        Screener t = s.offset(10).size(250).sort(SortField.ascending("ticker"));
        assertThat(t.offset()).isEqualTo(10);
        assertThat(t.size()).isEqualTo(250);
        assertThat(t.sort()).isEqualTo(SortField.ascending("ticker"));
        assertThat(t).isEqualTo(Screener.of(q).size(250).offset(10).sort(SortField.ascending("ticker")));
        assertThat(t.hashCode())
                .isEqualTo(Screener.of(q)
                        .size(250)
                        .offset(10)
                        .sort(SortField.ascending("ticker"))
                        .hashCode());
        assertThat(s).isNotEqualTo(t);
        assertThat(Screener.predefined(" day_gainers ").predefinedName()).isEqualTo("day_gainers");
        assertThat(Screener.predefined("day_gainers").query()).isNull();
        assertThat(Screener.predefined("x").toString()).contains("predefined=x");
    }

    @Test
    void invalidOptions() {
        Screener s = Screener.predefined("day_gainers");
        assertThatThrownBy(() -> s.size(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.size(251))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..250");
        assertThatThrownBy(() -> s.offset(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Screener.predefined(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Screener.of(null)).isInstanceOf(NullPointerException.class);
    }
}
