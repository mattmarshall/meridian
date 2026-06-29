// Swift theme binding — maps a `meridian.theme.v1.Theme` to SwiftUI styling and
// threads it through the view tree via the SwiftUI Environment. This is the
// SwiftUI half of meridian's orthogonal style layer: the views emit
// semantics-only SwiftUI and source EVERY color from the `MeridianTheme` in the
// environment, so no `.orange` / `.secondary` literal lives in the view code.
// One Theme drives every renderer; this is its SwiftUI expression (the analog
// of the web binding's `--mer-*` vars, the TUI's `Palette`, JavaFX's
// `MeridianTheme`).
//
// Xcode-gated: depends on the swift-protobuf type from //proto:MeridianThemeProto
// and on SwiftUI, so it builds only on a host with a full Xcode (swiftc). The
// proto type is `Meridian_Theme_V1_Theme` (swift-protobuf's naming for
// meridian.theme.v1.Theme).

import SwiftUI
import MeridianThemeProto

/// Which sub-palette of a Theme to render with. `dark` falls back to `light`
/// when unset (matching the web binding's `paletteFor`).
public enum MeridianMode {
    case light
    case dark
}

/// A SwiftUI-ready palette: the Theme's hex color roles resolved to SwiftUI
/// `Color`s. Built once from a `Theme` (or `.neutral` for an un-themed run) and
/// placed in the environment; views read it via `@Environment(\.meridianTheme)`.
public struct MeridianTheme: Equatable {
    public var bg: Color
    public var surface: Color
    public var fg: Color
    public var muted: Color
    public var border: Color
    public var accent: Color
    public var accentStrong: Color
    public var onAccent: Color
    public var danger: Color
    public var success: Color
    public var codeBackground: Color
    public var codeForeground: Color

    /// Body font size (points), from Typography.base_size_px.
    public var baseSize: CGFloat
    /// Default corner radius (points), from Metrics.radius_px.
    public var radius: CGFloat
    /// Base spacing unit (points), from Metrics.unit_px.
    public var unit: CGFloat

    public init(
        bg: Color, surface: Color, fg: Color, muted: Color, border: Color,
        accent: Color, accentStrong: Color, onAccent: Color,
        danger: Color, success: Color,
        codeBackground: Color, codeForeground: Color,
        baseSize: CGFloat = 14, radius: CGFloat = 6, unit: CGFloat = 4
    ) {
        self.bg = bg
        self.surface = surface
        self.fg = fg
        self.muted = muted
        self.border = border
        self.accent = accent
        self.accentStrong = accentStrong
        self.onAccent = onAccent
        self.danger = danger
        self.success = success
        self.codeBackground = codeBackground
        self.codeForeground = codeForeground
        self.baseSize = baseSize
        self.radius = radius
        self.unit = unit
    }

    /// Resolve a `meridian.theme.v1.Theme` for `mode` into SwiftUI colors.
    /// Empty / unparseable roles fall back to `.neutral`'s corresponding role,
    /// so a partial Theme still renders.
    public static func from(
        _ theme: Meridian_Theme_V1_Theme,
        mode: MeridianMode = .dark
    ) -> MeridianTheme {
        let palette: Meridian_Theme_V1_Palette
        switch mode {
        case .dark:
            // `dark` falls back to `light` when unset.
            palette = theme.hasDark ? theme.dark : theme.light
        case .light:
            palette = theme.light
        }
        let d = MeridianTheme.neutral
        var t = MeridianTheme(
            bg: Color(hex: palette.bg) ?? d.bg,
            surface: Color(hex: palette.surface) ?? d.surface,
            fg: Color(hex: palette.fg) ?? d.fg,
            muted: Color(hex: palette.muted) ?? d.muted,
            border: Color(hex: palette.border) ?? d.border,
            accent: Color(hex: palette.accent) ?? d.accent,
            accentStrong: Color(hex: palette.accentStrong) ?? d.accentStrong,
            onAccent: Color(hex: palette.onAccent) ?? d.onAccent,
            danger: Color(hex: palette.danger) ?? d.danger,
            success: Color(hex: palette.success) ?? d.success,
            codeBackground: Color(hex: palette.codeBg) ?? d.codeBackground,
            codeForeground: Color(hex: palette.codeFg) ?? d.codeForeground
        )
        if theme.hasTypography, theme.typography.baseSizePx > 0 {
            t.baseSize = CGFloat(theme.typography.baseSizePx)
        }
        if theme.hasMetrics {
            if theme.metrics.radiusPx > 0 { t.radius = CGFloat(theme.metrics.radiusPx) }
            if theme.metrics.unitPx > 0 { t.unit = CGFloat(theme.metrics.unitPx) }
        }
        return t
    }

    /// The neutral built-in theme — so the renderer is presentable un-skinned
    /// (the SwiftUI analog of meridian.css's neutral fallbacks / the TUI's
    /// `Palette::default()`). Intentionally brand-neutral; the fastverk look
    /// ships from `@brand` as a Theme.
    public static let neutral = MeridianTheme(
        bg: Color(red: 0x14 / 255, green: 0x16 / 255, blue: 0x18 / 255),
        surface: Color(red: 0x1c / 255, green: 0x1e / 255, blue: 0x24 / 255),
        fg: Color(white: 0xE6 / 255),
        muted: Color(white: 0x9A / 255),
        border: Color(white: 0x3A / 255),
        accent: Color(red: 0x6C / 255, green: 0x9C / 255, blue: 0xE6 / 255),
        accentStrong: Color(red: 0x4A / 255, green: 0x7A / 255, blue: 0xC4 / 255),
        onAccent: Color(red: 0x10 / 255, green: 0x12 / 255, blue: 0x16 / 255),
        danger: Color(red: 0xE0 / 255, green: 0x6C / 255, blue: 0x6C / 255),
        success: Color(red: 0x6C / 255, green: 0xC4 / 255, blue: 0x86 / 255),
        codeBackground: Color(red: 0x20 / 255, green: 0x22 / 255, blue: 0x28 / 255),
        codeForeground: Color(red: 0xD8 / 255, green: 0xD2 / 255, blue: 0xC4 / 255)
    )
}

// MARK: - Environment plumbing

private struct MeridianThemeKey: EnvironmentKey {
    static let defaultValue = MeridianTheme.neutral
}

public extension EnvironmentValues {
    /// The active meridian skin. Views read it via
    /// `@Environment(\.meridianTheme) private var theme`. Defaults to
    /// `.neutral` so an un-themed render still reads.
    var meridianTheme: MeridianTheme {
        get { self[MeridianThemeKey.self] }
        set { self[MeridianThemeKey.self] = newValue }
    }
}

public extension View {
    /// Apply a meridian theme to this view subtree (sets the environment value
    /// and the canvas background/foreground so the whole renderer is skinned).
    func meridianTheme(_ theme: MeridianTheme) -> some View {
        environment(\.meridianTheme, theme)
            .foregroundStyle(theme.fg)
            .background(theme.bg)
    }

    /// Convenience: resolve + apply a `meridian.theme.v1.Theme` for `mode`.
    func meridianTheme(_ theme: Meridian_Theme_V1_Theme, mode: MeridianMode = .dark) -> some View {
        meridianTheme(MeridianTheme.from(theme, mode: mode))
    }
}

// MARK: - Hex parsing

public extension Color {
    /// Parse a CSS-style `#RRGGBB` (or `#RRGGBBAA`) hex string. Returns `nil`
    /// for empty / malformed input so callers can fall back to a default role.
    init?(hex: String) {
        let h = hex.hasPrefix("#") ? String(hex.dropFirst()) : nil
        guard let h, h.count == 6 || h.count == 8 else { return nil }
        guard let value = UInt64(h, radix: 16) else { return nil }
        let r, g, b: Double
        if h.count == 8 {
            // #RRGGBBAA — alpha ignored (the canvas is opaque), like the TUI.
            r = Double((value >> 24) & 0xFF) / 255
            g = Double((value >> 16) & 0xFF) / 255
            b = Double((value >> 8) & 0xFF) / 255
        } else {
            r = Double((value >> 16) & 0xFF) / 255
            g = Double((value >> 8) & 0xFF) / 255
            b = Double(value & 0xFF) / 255
        }
        self.init(red: r, green: g, blue: b)
    }
}
