# Meridian Declarative Worker — Action/Effect Layer

## Purpose

A declarative contract for worker actions and effects, so UI logic and
orchestration can be described as data rather than imperative code. The
goals:

- Predictable, serializable, inspectable worker logic
- Easier testing, replay, and tooling
- Future compatibility with non-JS runtimes (e.g. Rust/Wasm)

## Concepts

### Action

A declarative description of an intent or event. Actions are dispatched
by the host (UI / main thread) or by effects within the worker.

```json
{
  "type": "ACTION_TYPE",
  "payload": { },
  "meta": { }
}
```

- `type`: string identifier (e.g. `search/submit`, `results/received`)
- `payload`: action data (optional)
- `meta`: optional metadata (e.g. correlation id)

### Effect

A declarative description of a side effect to perform in response to an
action.

```json
{
  "effect": "EFFECT_TYPE",
  "params": { },
  "onSuccess": [ ],
  "onError": [ ]
}
```

- `effect`: string identifier (e.g. `fetch`, `postMessage`, `setState`)
- `params`: effect parameters
- `onSuccess` / `onError`: actions to dispatch on success/failure

### Transition table

A mapping from action types to effect lists.

```json
{
  "ACTION_TYPE": [ ]
}
```

Each action type maps to an array of effects to perform in order.
Effects can dispatch further actions, enabling chaining.

### State

A plain object representing worker-local state. Effects update state
via the `setState` effect; the runtime owns the state machine.

## Example

```json
{
  "transitions": {
    "search/submit": [
      {
        "effect": "fetch",
        "params": { "url": "/api/search", "body": "${payload.query}" },
        "onSuccess": [{ "type": "results/received", "payload": "${result}" }],
        "onError": [{ "type": "results/error", "payload": "${error}" }]
      }
    ],
    "results/received": [
      { "effect": "setState", "params": { "results": "${payload}" } },
      { "effect": "postMessage", "params": { "type": "results/ready", "payload": "${payload}" } }
    ]
  },
  "initialState": { "results": [] }
}
```

## Built-in effects

- `fetch`: perform a network request
- `setState`: update worker-local state
- `postMessage`: send a message to the main thread

The runtime is extensible — additional effects can be wired in.

## Transition rules

1. When an action is dispatched, the runtime looks up its `type` in the
   transition table.
2. For each effect, the runtime executes it in order.
3. Effects may dispatch further actions via `onSuccess` / `onError`.
4. State is updated only via `setState` effects.

## Integration

- The worker runtime loads the declarative contract (JSON or JS object).
- Host dispatches actions via `postMessage`.
- The worker interprets actions, executes effects, updates state, and
  communicates results back to the host.

See [`examples/declarative_worker.ts`](../examples/declarative_worker.ts)
and [`examples/declarative_worker_contract.ts`](../examples/declarative_worker_contract.ts).
