/** Converts yfin results into DFLib data frames. */
@SuppressWarnings("requires-automatic")
module io.andreygrigoriev.yfin.dflib {
    requires transitive io.andreygrigoriev.yfin;
    requires transitive dflib;
    requires static org.jspecify;

    exports io.andreygrigoriev.yfin.dflib;
}
