# Meridian Rust workspace

Three crates implementing Meridian's proto-driven UI framework for
native Rust and (via the `wasm` feature on `meridian-uiview`) the TS
web renderer.

| Crate | What |
|---|---|
| `uiview` | Platform-neutral core: prost-generated `PanelDescriptor` types, `ProtoPaths` field accessor, `RequestBuilder` request assembler, per-cell formatting. Optional `wasm` feature exposes wasm-bindgen wrappers consumed by the TS host. |
| `tui` | `ratatui` renderer. `PanelView` is a stateful widget that draws one `PanelDescriptor`; `PanelAppState` holds the catalog + runtime context + host's `RpcInvoker`. |
| `tui-demo` | Standalone binary rendering mocked Pinax-style panels in a terminal. |

## Native build

```bash
cargo build
cargo test
cargo run -p meridian-tui-demo
```

Keys in the TUI demo:
- `Tab` / `Shift-Tab` — cycle panels
- `↑` / `↓` — move row selection
- `q` — quit

## wasm build (for the TS web renderer)

```bash
wasm-pack build uiview --features wasm
```

Output lands in `uiview/pkg/`. The example at
`../examples/uiview-demo/` imports the generated `.js` + `.wasm`
directly; consumers integrating into a real build can publish the
`pkg/` to npm or copy it into their bundler input.

`wasm-pack` itself: `cargo install wasm-pack` (one-time).

## Why JSON-shaped flow rather than typed prost messages?

prost generates struct types but no runtime descriptors. A generic
"look up `subject.claim.claim_text`" can't be done reflectively
without `prost-reflect`'s extra weight. Connect-ES + grpc-web's JSON
mode already speak proto-as-JSON, and hosts that talk gRPC native
(tonic) can marshal to JSON at the boundary cheaply for UI volumes.
So the core operates on `serde_json::Value` for all rows, requests,
and responses; only the `PanelDescriptor` itself flows as a typed
prost message.

The `build.rs` adds `serde::{Serialize, Deserialize}` to every
generated type so JSON-shaped descriptors from the TS / wasm boundary
deserialize without a manual encoder.

## Bazel integration

Deferred. Native crates build via plain `cargo`; the wasm bundle via
`wasm-pack`. Consumers that need Bazel can wire `rules_rust` +
`rules_rust_wasm_bindgen` into `MODULE.bazel` and host these crates
there; nothing in the framework requires it.
