# Meridian uiview web-renderer demo

A standalone HTML page that mounts the Meridian UI framework's web
renderer against mocked RPC data. The same `PanelDescriptor`s drive
the JavaFX desktop UI and the Rust TUI demo — this just swaps in a
DOM + wasm-bindgen rendering layer.

## Run it

The wasm bundle isn't checked in (the `rust/uiview/pkg/` directory
is gitignored by wasm-pack convention). Build it first:

```bash
cd ../../rust
wasm-pack build uiview --features wasm
```

Then serve the meridian repo root over a static HTTP server. Any
static server works; the simplest is Python's:

```bash
cd ../..
python3 -m http.server 8000
```

Then open <http://localhost:8000/examples/uiview-demo/> in a browser.

## What you'll see

Three tabs at the top — **Claims**, **Review tickets**, **SPARQL** —
each driven by a `PanelDescriptor`:

- **Claims** / **Review tickets** are `TablePanel` descriptors. The
  renderer calls `wasm.buildPopulateRequest` for each, dispatches via
  the mocked `RpcInvoker` (canned JSON in `demo.js`), and feeds the
  response through `wasm.renderTable` to get back rendered rows
  matching what JavaFX / TUI produce.
- **SPARQL** is an `AdhocPanel`. The host registers a factory keyed by
  `handler_id` — for this demo, a small textarea placeholder — and
  the renderer calls it instead of building a table.

## Architecture

```
descriptor (JSON)
      │
      ├─ buildPopulateRequest(descriptor, context) ──► JSON request
      │       (Rust → wasm-bindgen → TS)
      │
      ├─ invoker.invoke(service, method, request) ───► JSON response
      │       (host-supplied; mocked here, tonic / grpc-web in prod)
      │
      └─ renderTable(descriptor, response) ──► [{ raw, cells }]
              (Rust → wasm-bindgen → TS → DOM)
```

The same flow with `ratatui` widgets instead of `<table>` elements is
what `rust/tui-demo` does. Same descriptor, same Rust core, different
host.
