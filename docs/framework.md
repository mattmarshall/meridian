# Meridian as a framework

Meridian is a UI *framework*, not a widget toolkit. You don't draw screens;
you **declare** them as data and let a per-platform renderer host them. Two
ideas make that work, and they're enforced by the type system rather than by
convention:

1. **Three orthogonal layers** — semantics, theme, renderers.
2. **Widgets as plugins** — a feature contributes panels to a host through a
   manifest; it doesn't reach into the host's UI.

## The three-layer model

| Layer | Package / path | Carries | Never carries |
|---|---|---|---|
| **Semantics** | `meridian.ui.v1` ([`proto/panel.proto`](../proto/panel.proto) & friends) | what a view *is and does* — which RPC populates it, how the response maps to rows/columns, what actions it exposes | **style** |
| **Theme** | `meridian.theme.v1` ([`proto/theme.proto`](../proto/theme.proto)) | how it *looks* — color roles, typography, metrics | **semantics** |
| **Renderers** | [`src/uiview`](../src/uiview) (web), [`rust/tui`](../rust/tui), [`java/javafx`](../java/javafx), [`swift`](../swift) | bind `(descriptor, theme) → native UI` | platform leakage into the contract |

The split is structural: `meridian.ui.v1` and `meridian.theme.v1` are
**separate `proto_library` targets** (`uiview_proto` and `theme_proto` in
[`proto/BUILD.bazel`](../proto/BUILD.bazel)) with no edge between them. A
renderer — or a skin module like `@brand` — can depend on the Theme contract
without pulling the panel descriptors, and vice versa. A `PanelDescriptor`
literally *cannot* name a color; a `Theme` literally *cannot* name an RPC.

### Why this matters

- **One descriptor, every platform.** The Java / Rust / TS / Swift renderers
  consume the same `PanelDescriptor` protos. Adding a platform is "write one
  renderer over the same contract," not "port the screens."
- **One skin, every platform.** A `Theme` is renderer-agnostic data, so a
  single skin authored once renders consistently on every surface. The
  fastverk brand identity ships from `@brand` as one
  `meridian.theme.v1.Theme` (`@brand//skins:fastverk`) and themes the web
  console, the macOS app, the JavaFX desktop UI, and the TUI alike.
- **Meridian stays brand-neutral.** No fastverk color lives in meridian; each
  renderer ships a neutral built-in palette so it's presentable un-skinned,
  and the brand is supplied from outside.

### How each renderer binds a Theme

Every renderer maps the *same* `Theme` tokens to its platform's style
primitive. The roles are semantic (`accent`, not "amber"), so the mapping is
mechanical:

| Renderer | Theme binding | Maps a `Theme` to |
|---|---|---|
| Web | [`theme/web/theme.ts`](../theme/web/theme.ts) + [`theme/web/meridian.css`](../theme/web) | `--mer-*` CSS custom properties (`applyTheme` / `themeToCss`) |
| TUI (ratatui) | [`rust/tui/src/theme.rs`](../rust/tui/src/theme.rs) | a `Palette` of `ratatui::style::Color::Rgb` + pre-composed `Style`s |
| JavaFX | [`java/javafx/MeridianTheme.java`](../java/javafx/MeridianTheme.java) | JavaFX looked-up colors + `-fx-*` style strings |
| SwiftUI | [`swift/Sources/MeridianUI/Theme.swift`](../swift/Sources/MeridianUI/Theme.swift) | SwiftUI `Color`s via an `EnvironmentValues` key |

Each binding parses the neutral interchange form (`#RRGGBB` hex), falls back
to its neutral default for any unset role, and — critically — is the *only*
place a color literal appears. The renderers' widget code sources all look
from the bound theme: there are no `Color::`, `-fx-text-fill: #…`, or
`.orange` literals left in the panel widgets. See
[`theme/README.md`](../theme/README.md) for the full token list.

## Widgets as plugins

A "widget" in meridian is not a subclass you instantiate — it's a **module
that contributes panels** to a host. The contribution contract lives in fvkit
([`fvkit//proto/fastverk/plugin/v1/manifest.proto`](https://registry.fastverk.com)):
a plugin ships a `PluginManifest` declaring the gRPC services it implements
(its "interfaces") and the meridian UI it contributes via `PanelContribution`:

```proto
// fastverk.plugin.v1 (fvkit)
message PanelContribution {
  // Path to the plugin's panels.binpb (a meridian PanelBundle), relative to
  // its resource dir.
  string bundle_path = 1;
  // AdhocPanel handler_ids this plugin registers (custom, non-table UI).
  repeated string adhoc_handler_ids = 2;
}
```

The flow is:

1. A feature is a **narrowly-scoped plugin** that implements one or more gRPC
   services and ships a `panels.binpb` — a meridian `PanelBundle` of
   `PanelDescriptor`s declaring its UI.
2. The host (`fvd`) reads each plugin's manifest, composes every plugin's
   bundle into one panel set, and routes `(service, method)` calls to the
   owning plugin ("QueryRPC").
3. The renderer hosts those descriptors. Generic shapes (`TablePanel`,
   `LroPanel`, `PromptPanel`, …) render with zero per-feature code. Bespoke
   layouts that don't fit a shape route through the **`AdhocPanel`** variant,
   which names a host-side `handler_id`; the host registers a factory for that
   id (e.g. JavaFx `renderer.registerAdhoc(id, factory)`).

So a new feature is "one plugin + one panel bundle (+ optionally one adhoc
factory)" — it never edits the host's UI, and it inherits the active skin for
free, because the host applies the `Theme` once at the renderer.

### Putting it together

```
feature plugin                 host (fvd / app)              renderer + skin
──────────────                 ─────────────────             ───────────────
implements gRPC service(s)  →  composes PanelBundles      →  binds (descriptor, Theme)
ships panels.binpb             routes QueryRPC                → native UI, skinned once
declares PanelContribution     applies @brand Theme           by the active Theme
```

Semantics from the plugin, style from the skin, native UI from the renderer —
three independent axes, each a proto contract, each substitutable without
touching the others.
