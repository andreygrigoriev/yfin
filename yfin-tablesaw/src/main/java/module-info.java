/** Converts yfin results into Tablesaw tables. */
@SuppressWarnings("requires-automatic")
module io.andreygrigoriev.yfin.tablesaw {
    requires transitive io.andreygrigoriev.yfin;
    requires transitive tablesaw.core;
    requires static org.jspecify;

    exports io.andreygrigoriev.yfin.tablesaw;
}
