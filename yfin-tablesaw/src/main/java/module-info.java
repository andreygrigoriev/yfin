/** Converts yfin results into Tablesaw tables. */
@SuppressWarnings("requires-automatic")
module io.github.andreygrigoriev.yfin.tablesaw {
    requires transitive io.github.andreygrigoriev.yfin;
    requires transitive tablesaw.core;
    requires static org.jspecify;

    exports io.github.andreygrigoriev.yfin.tablesaw;
}
