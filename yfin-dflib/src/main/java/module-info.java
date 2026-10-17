/** Converts yfin results into DFLib data frames. */
@SuppressWarnings("requires-automatic")
module io.github.andreygrigoriev.yfin.dflib {
    requires transitive io.github.andreygrigoriev.yfin;
    requires transitive dflib;
    requires static org.jspecify;

    exports io.github.andreygrigoriev.yfin.dflib;
}
