/**
 * Price repair, ported from yfinance's {@code PriceHistory._fix_*} family: currency unit mix-ups,
 * missing prices (reconstructed from finer intervals), missing split adjustments, bad dividends and
 * dividend adjustments, and capital gains counted twice. Works on a mutable column table
 * ({@link io.github.andreygrigoriev.yfin.internal.repair.Frame}). Not exported.
 */
@NullMarked
package io.github.andreygrigoriev.yfin.internal.repair;

import org.jspecify.annotations.NullMarked;
