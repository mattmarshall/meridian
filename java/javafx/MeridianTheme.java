package meridian.ui.javafx;

import javafx.scene.Parent;
import meridian.theme.v1.Palette;
import meridian.theme.v1.Theme;
import meridian.theme.v1.Typography;

// JavaFX theme binding — maps a meridian.theme.v1.Theme to JavaFX styling. This
// is the JavaFX half of meridian's orthogonal style layer: the cards
// (DescribedTableCard / DescribedLroCard / LroCard) emit semantics-only widget
// trees and source EVERY color / font from the MeridianTheme handed to them, so
// no `-fx-text-fill: #555` literal lives in the card code. One Theme drives
// every renderer; this is its JavaFX expression (the analog of the web binding's
// `--mer-*` vars and the TUI's `Palette`).
//
// Two surfaces:
//   * `rootStyle()` — the look-bearing CSS for the renderer's container: maps
//     the palette to JavaFX *looked-up colors* (-fv-bg, -fv-fg, …) on the root,
//     so descendants can reference them, plus the base background/font/text
//     fill. Apply with `applyTo(parent)`.
//   * per-role style strings (`headerStyle()`, `metaStyle()`, …) the cards set
//     on their Labels / controls.
//
// Colors arrive as CSS hex (#RRGGBB) — JavaFX consumes that form directly, so
// no parsing is needed (unlike the TUI's Color::Rgb).
public final class MeridianTheme {

  private final Palette palette;
  private final Typography typography;

  private MeridianTheme(Palette palette, Typography typography) {
    this.palette = palette;
    this.typography = typography;
  }

  /** Whether to render with a Theme's light or dark palette. */
  public enum Mode { LIGHT, DARK }

  /**
   * Resolve a {@code meridian.theme.v1.Theme} for {@code mode}. {@code dark}
   * falls back to {@code light} when unset (matching the web binding's
   * {@code paletteFor}); per-role empties fall back to the neutral default.
   */
  public static MeridianTheme of(Theme theme, Mode mode) {
    Palette base = mode == Mode.DARK
        ? (theme.hasDark() ? theme.getDark() : theme.getLight())
        : theme.getLight();
    Palette merged = mergeOverDefault(base);
    Typography typo = mergeTypography(theme.hasTypography() ? theme.getTypography() : null);
    return new MeridianTheme(merged, typo);
  }

  /**
   * The neutral built-in theme, so the renderer is presentable un-skinned (the
   * JavaFX analog of meridian.css's neutral fallbacks / the TUI's
   * {@code Palette::default()}). Intentionally brand-neutral — the fastverk look
   * ships from {@code @brand} as a Theme.
   */
  public static MeridianTheme neutral() {
    return new MeridianTheme(defaultPalette(), defaultTypography());
  }

  // ── Container look ──────────────────────────────────────────────────────

  /**
   * Inline CSS for the renderer's root container: declares the palette as
   * looked-up colors (so descendants can reference {@code -fv-accent} etc.) and
   * sets the base background, text fill, and font.
   */
  public String rootStyle() {
    return new StringBuilder()
        .append(lookup("-fv-bg", palette.getBg()))
        .append(lookup("-fv-surface", palette.getSurface()))
        .append(lookup("-fv-fg", palette.getFg()))
        .append(lookup("-fv-muted", palette.getMuted()))
        .append(lookup("-fv-border", palette.getBorder()))
        .append(lookup("-fv-accent", palette.getAccent()))
        .append(lookup("-fv-accent-strong", palette.getAccentStrong()))
        .append(lookup("-fv-on-accent", palette.getOnAccent()))
        .append(lookup("-fv-danger", palette.getDanger()))
        .append(lookup("-fv-success", palette.getSuccess()))
        .append("-fx-background-color: -fv-bg;")
        .append("-fx-text-fill: -fv-fg;")
        .append(fontFamily())
        .toString();
  }

  /** Apply {@link #rootStyle()} to a renderer container. */
  public void applyTo(Parent root) {
    root.setStyle(rootStyle());
  }

  // ── Per-role styles the cards source from ───────────────────────────────

  /** Section / panel title — accent-colored, bold, heading size. */
  public String headerStyle() {
    return "-fx-font-weight: bold;"
        + "-fx-font-size: " + headerSizePx() + "px;"
        + "-fx-text-fill: " + hex(palette.getAccent()) + ";"
        + fontFamily();
  }

  /** Secondary / meta text (row counts, status, hints). */
  public String metaStyle() {
    return "-fx-text-fill: " + hex(palette.getMuted()) + ";";
  }

  /** Affirmative / success status. */
  public String successStyle() {
    return "-fx-text-fill: " + hex(palette.getSuccess()) + ";";
  }

  /** Error / failure status. */
  public String dangerStyle() {
    return "-fx-text-fill: " + hex(palette.getDanger()) + ";";
  }

  public Palette palette() { return palette; }
  public Typography typography() { return typography; }

  // ── Internals ───────────────────────────────────────────────────────────

  private String fontFamily() {
    String sans = typography.getSans();
    return sans.isEmpty() ? "" : "-fx-font-family: " + sans + ";";
  }

  private int headerSizePx() {
    int base = typography.getBaseSizePx();
    return base > 0 ? base : 14;
  }

  private static String lookup(String name, String hex) {
    return name + ": " + hex(hex) + ";";
  }

  /** Normalize a palette hex to a JavaFX-safe color literal. */
  private static String hex(String value) {
    return (value == null || value.isEmpty()) ? "transparent" : value;
  }

  /** Fill any empty role on {@code base} from the neutral default palette. */
  private static Palette mergeOverDefault(Palette base) {
    Palette d = defaultPalette();
    return Palette.newBuilder()
        .setBg(orElse(base.getBg(), d.getBg()))
        .setSurface(orElse(base.getSurface(), d.getSurface()))
        .setFg(orElse(base.getFg(), d.getFg()))
        .setMuted(orElse(base.getMuted(), d.getMuted()))
        .setBorder(orElse(base.getBorder(), d.getBorder()))
        .setAccent(orElse(base.getAccent(), d.getAccent()))
        .setAccentStrong(orElse(base.getAccentStrong(), d.getAccentStrong()))
        .setOnAccent(orElse(base.getOnAccent(), d.getOnAccent()))
        .setDanger(orElse(base.getDanger(), d.getDanger()))
        .setSuccess(orElse(base.getSuccess(), d.getSuccess()))
        .setCodeBg(orElse(base.getCodeBg(), d.getCodeBg()))
        .setCodeFg(orElse(base.getCodeFg(), d.getCodeFg()))
        .build();
  }

  private static Typography mergeTypography(Typography t) {
    Typography d = defaultTypography();
    if (t == null) return d;
    return Typography.newBuilder()
        .setSans(orElse(t.getSans(), d.getSans()))
        .setMono(orElse(t.getMono(), d.getMono()))
        .setBaseSizePx(t.getBaseSizePx() > 0 ? t.getBaseSizePx() : d.getBaseSizePx())
        .setHeadingWeight(t.getHeadingWeight() > 0 ? t.getHeadingWeight() : d.getHeadingWeight())
        .setBodyWeight(t.getBodyWeight() > 0 ? t.getBodyWeight() : d.getBodyWeight())
        .setHeadingTracking(orElse(t.getHeadingTracking(), d.getHeadingTracking()))
        .build();
  }

  private static String orElse(String v, String fallback) {
    return (v == null || v.isEmpty()) ? fallback : v;
  }

  // Neutral warm-dark default — mirrors the TUI's Palette::default() and
  // meridian.css's fallbacks so all four renderers read the same un-skinned.
  private static Palette defaultPalette() {
    return Palette.newBuilder()
        .setBg("#141618")
        .setSurface("#1c1e24")
        .setFg("#E6E6E6")
        .setMuted("#9A9A9A")
        .setBorder("#3A3A3A")
        .setAccent("#6C9CE6")
        .setAccentStrong("#4A7AC4")
        .setOnAccent("#101216")
        .setDanger("#E06C6C")
        .setSuccess("#6CC486")
        .setCodeBg("#202228")
        .setCodeFg("#D8D2C4")
        .build();
  }

  private static Typography defaultTypography() {
    return Typography.newBuilder()
        .setSans("")
        .setMono("")
        .setBaseSizePx(14)
        .setHeadingWeight(600)
        .setBodyWeight(400)
        .setHeadingTracking("")
        .build();
  }
}
