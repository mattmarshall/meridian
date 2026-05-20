# Meridian

Lightweight web component runtime. The TypeScript surface is a tiny
toolkit — typed worker controller, DOM patch helpers, template loader,
HTML escape, UI-kit CSS, and a proto-JSON message envelope — and the
Bazel surface is a small rule set that captures the component graph
declaratively and emits a JSON manifest of it.

It is not a framework. It is the substrate a few worker-backed
components in [hrcrawl](https://github.com/mattmarshall/hrcrawl) and
sibling projects share.

## What's in it

| Surface | Where |
|---|---|
| Worker controller + protocol | [`src/workers.ts`](src/workers.ts) |
| Proto-JSON envelope helpers   | [`src/proto_json.ts`](src/proto_json.ts) |
| DOM patch helpers             | [`src/patch.ts`](src/patch.ts) |
| HTML escape, short-name       | [`src/dom.ts`](src/dom.ts) |
| Template fragment loader      | [`src/templates.ts`](src/templates.ts) |
| JSON `fetch` wrapper          | [`src/api.ts`](src/api.ts) |
| Default UI-kit CSS            | [`src/styles.ts`](src/styles.ts) |
| Declarative worker runtime    | [`src/declarative_worker_runtime.ts`](src/declarative_worker_runtime.ts) |
| Worker command JSON schema    | [`src/worker-command.schema.json`](src/worker-command.schema.json) |
| Bazel rules                   | [`bazel/defs.bzl`](bazel/defs.bzl) |

## Using meridian from a Bazel workspace

### Via local checkout (fast iteration)

Clone meridian as a sibling of the consuming repo, then in the
consumer's `MODULE.bazel`:

```python
bazel_dep(name = "meridian", version = "0.1.0")
local_path_override(
    module_name = "meridian",
    path = "../meridian",
)
```

In a `BUILD.bazel`:

```python
load("@meridian//bazel:defs.bzl", "meridian_component", "meridian_worker_component", "meridian_component_manifest")

meridian_worker_component(
    name = "my_widget",
    js = "my_widget.js",
    template = "my_widget.html",
    worker = "my_widget.worker.js",
    services = ["my_widget_service.js"],
    render_mode = "view_model",
)
```

And the TS surface from a consumer's source:

```ts
import { MeridianWorkerController, escHtml, patchText } from '@meridian/core';
```

The consumer's `tsconfig.json` needs a `paths` entry pointing at this
checkout:

```json
{
  "compilerOptions": {
    "paths": {
      "@meridian/core": ["../meridian/src/index.ts"],
      "@meridian/core/*": ["../meridian/src/*"]
    }
  }
}
```

## Rule API

### `meridian_component`

Declares one component:

- `js` — JavaScript implementation (`.js`)
- `template` — HTML template (`.html`)
- `worker` — optional worker entrypoint (`.js`)
- `services` — optional service modules (`.js`)
- `worker_mode` — `none` (default), `dedicated`, or `shared`
- `render_mode` — `view_model` (default) or `html_fragment`
- `deps` — other `meridian_component` targets

Validation:
- `worker_mode != none` requires `worker`
- `worker_mode == none` rejects `worker`
- `render_mode == html_fragment` requires a worker-backed component

### `meridian_worker_component`

Convenience macro: same attrs as `meridian_component`, but `worker_mode`
defaults to `dedicated` and is required to be a worker mode.

### `meridian_component_manifest`

Walks the transitive `deps` of one or more `meridian_component` targets
via an aspect and emits a JSON file listing every component, its JS /
template / worker / services paths, modes, and direct deps. Useful as a
runtime-loadable component registry.

## Development

```bash
bazel test //tests:unit          # vitest under bazel via rules_vite
bazel build //src:core           # compile the TS surface
```

The repo uses the [fastverk/bazel-registry](https://github.com/fastverk/bazel-registry)
for `rules_vite` (dev-only test runner). Consumers don't need it.

## Layout

```
meridian/
├── bazel/defs.bzl              Bazel rules
├── src/                        TS surface + worker-command schema
├── tests/                      vitest under rules_vite
├── examples/                   declarative worker contract + worker
└── docs/declarative_worker_spec.md
```

## License

TBD (private repo).
