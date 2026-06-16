import { parseProtoJsonEnvelope, toProtoJsonEnvelope } from './proto_json.js';

const WORKER_MSG_BASE = 'type.googleapis.com/savvifi.hrcrawl.ui.v1';
const WORKER_INIT_TYPE = `${WORKER_MSG_BASE}.WorkerInit`;
const WORKER_ATTRIBUTE_CHANGED_TYPE = `${WORKER_MSG_BASE}.WorkerAttributeChanged`;
const WORKER_EVENT_TYPE = `${WORKER_MSG_BASE}.WorkerEvent`;
const WORKER_DISPOSE_TYPE = `${WORKER_MSG_BASE}.WorkerDispose`;

// ── Worker module loading with import-map resolution ──────────────────────
//
// A module worker (`new Worker(url, { type: 'module' })`) does NOT inherit the
// document's import map, so any bare specifier anywhere in the worker's module
// graph (a bare specifier such as meridian-core/workers.js) fails to resolve.
// To make import maps work for workers, we fetch the worker's module graph,
// rewrite every specifier to a resolved URL, and stitch the modules together as
// a graph of object-URL ("blob:") modules the worker can load directly.

function readImportMap(): Record<string, string> {
  if (typeof document === 'undefined') return {};
  const el = document.querySelector('script[type="importmap"]');
  if (!el || !el.textContent) return {};
  try {
    const parsed = JSON.parse(el.textContent);
    return (parsed && parsed.imports) || {};
  } catch {
    return {};
  }
}

function resolveSpecifier(
  spec: string,
  baseUrl: string,
  imports: Record<string, string>,
): string {
  // Relative / absolute specifiers resolve against the importing module.
  if (spec.startsWith('/') || spec.startsWith('./') || spec.startsWith('../')) {
    return new URL(spec, baseUrl).href;
  }
  // Import-map targets resolve against the document base.
  const base = (typeof document !== 'undefined' && document.baseURI) || baseUrl;
  // Bare specifier: resolve via the import map (longest matching prefix wins).
  let best: { prefix: string; target: string } | null = null;
  for (const prefix in imports) {
    const isPrefix = prefix.endsWith('/') && spec.startsWith(prefix);
    if (spec === prefix || isPrefix) {
      if (!best || prefix.length > best.prefix.length) {
        best = { prefix, target: imports[prefix] };
      }
    }
  }
  if (best) {
    return new URL(best.target + spec.slice(best.prefix.length), base).href;
  }
  return new URL(spec, base).href; // unmapped — best effort
}

// Captures the module specifier of static import / export-from, side-effect,
// and dynamic-import statements. The static forms are anchored to line start
// (m flag) so a specifier that appears inside a comment or prose is not matched.
const WORKER_IMPORT_RE =
  /^[ \t]*import\s+(?:[^'"();\n]*?\sfrom\s+)?['"]([^'"]+)['"]|^[ \t]*export\s+[^'"();\n]*?\sfrom\s+['"]([^'"]+)['"]|\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/gm;

async function bundleModuleWorker(entryUrl: string): Promise<string> {
  const imports = readImportMap();
  const blobs = new Map<string, string>(); // absolute module URL -> blob URL
  const inflight = new Map<string, Promise<string>>();

  async function build(absUrl: string, stack: string[], prelude = ''): Promise<string> {
    const done = blobs.get(absUrl);
    if (done) return done;
    if (stack.indexOf(absUrl) !== -1) {
      throw new Error(`Cyclic worker module import: ${absUrl}`);
    }
    const pending = inflight.get(absUrl);
    if (pending) return pending;
    const task = (async () => {
      const res = await fetch(absUrl, { credentials: 'same-origin' });
      if (!res.ok) {
        throw new Error(`Failed to fetch worker module ${absUrl} (${res.status})`);
      }
      const src = await res.text();
      const childStack = stack.concat(absUrl);
      const specs = new Set<string>();
      WORKER_IMPORT_RE.lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = WORKER_IMPORT_RE.exec(src)) !== null) {
        const spec = match[1] || match[2] || match[3];
        if (spec) specs.add(spec);
      }
      const mapping = new Map<string, string>();
      for (const spec of specs) {
        mapping.set(spec, await build(resolveSpecifier(spec, absUrl, imports), childStack));
      }
      WORKER_IMPORT_RE.lastIndex = 0;
      const rewritten = src.replace(WORKER_IMPORT_RE, (full, s1, s2, s3) => {
        const spec = s1 || s2 || s3;
        const blob = spec ? mapping.get(spec) : undefined;
        return blob ? full.replace(spec, blob) : full;
      });
      const url = URL.createObjectURL(
        new Blob([prelude + rewritten], { type: 'text/javascript' }),
      );
      blobs.set(absUrl, url);
      return url;
    })();
    inflight.set(absUrl, task);
    return task;
  }

  const base = (typeof document !== 'undefined' && document.baseURI) || entryUrl;
  const entryHref = new URL(entryUrl, base).href;
  // The worker runs from a blob: URL, against which root-relative requests like
  // fetch('/api/…') cannot resolve (a blob: URL is not a valid base). Re-root
  // the worker's relative fetches against the worker's original URL so its
  // runtime requests keep resolving to the right origin.
  const prelude =
    '(function(){var B=' + JSON.stringify(entryHref) + ';var of=self.fetch;' +
    'if(of){self.fetch=function(i,o){try{if(typeof i==="string")i=new URL(i,B).href;}catch(e){}' +
    'return of.call(self,i,o);};}})();\n';
  return build(entryHref, [], prelude);
}

// Create a module worker whose bare specifiers resolve via the page import map.
// Falls back to a direct module worker if bundling fails (correct for graphs
// the browser can already resolve on its own, e.g. all-relative).
async function createResolvedModuleWorker(url: string): Promise<Worker> {
  try {
    return new Worker(await bundleModuleWorker(url), { type: 'module' });
  } catch {
    return new Worker(url, { type: 'module' });
  }
}

export class MeridianWorkerController {
  private _url: string;
  private _mode: string;
  private _onRenderModel?: (model: any) => void;
  private _onEmit?: (msg: any) => void;
  private _onStatus?: (msg: any) => void;
  private _worker: Worker | null = null;
  private _messagePort: Worker | null = null;
  private _pending: any[] = [];
  private _ready = false;
  private _starting = false;
  private _disposed = false;

  constructor(
    url: string,
    {
      mode = 'dedicated',
      onRenderModel,
      onEmit,
      onStatus,
    }: {
      mode?: string;
      onRenderModel?: (model: any) => void;
      onEmit?: (msg: any) => void;
      onStatus?: (msg: any) => void;
    } = {},
  ) {
    this._url = url;
    this._mode = mode;
    this._onRenderModel = onRenderModel;
    this._onEmit = onEmit;
    this._onStatus = onStatus;
    this._handleMessage = this._handleMessage.bind(this);
    this._handleError = this._handleError.bind(this);
  }

  start(payload: any = {}) {
    if (this._starting || this._messagePort) return;
    if (this._mode !== 'dedicated') {
      this._onStatus?.({
        level: 'error',
        message: `Unsupported Meridian worker mode: ${this._mode}`,
      });
      return;
    }
    if (typeof Worker === 'undefined') {
      this._onStatus?.({
        level: 'error',
        message: 'Web Workers are unavailable in this browser.',
      });
      return;
    }
    this._starting = true;
    // Queue init now; it (and any messages sent before the worker is ready)
    // flush once the resolved module worker is created.
    this._post({
      type: 'init',
      payload,
      envelope: toProtoJsonEnvelope(WORKER_INIT_TYPE, { payload }),
    });
    createResolvedModuleWorker(this._url).then(
      (worker) => {
        if (this._disposed) {
          worker.terminate();
          return;
        }
        this._worker = worker;
        this._messagePort = worker;
        worker.addEventListener('message', this._handleMessage);
        worker.addEventListener('error', this._handleError);
        worker.addEventListener('messageerror', this._handleError);
        this._ready = true;
        const queued = this._pending;
        this._pending = [];
        for (const msg of queued) worker.postMessage(msg);
      },
      (error: any) => {
        this._starting = false;
        const reason = error?.message || String(error);
        this._onStatus?.({
          level: 'error',
          message: `Failed to start worker ${this._url}: ${reason}`,
        });
      },
    );
  }

  private _post(message: any) {
    if (this._worker && this._ready) {
      this._worker.postMessage(message);
    } else {
      this._pending.push(message);
    }
  }

  updateAttribute(name: string, value: any) {
    this._post({
      type: 'attributeChanged',
      name,
      value,
      envelope: toProtoJsonEnvelope(WORKER_ATTRIBUTE_CHANGED_TYPE, { name, value }),
    });
  }

  dispatch(name: string, payload: any) {
    this._post({
      type: 'event',
      name,
      payload,
      envelope: toProtoJsonEnvelope(WORKER_EVENT_TYPE, { name, payload }),
    });
  }

  dispose() {
    if (this._disposed) return;
    this._disposed = true;
    this._pending = [];
    if (this._worker && this._ready) {
      this._worker.postMessage({
        type: 'dispose',
        envelope: toProtoJsonEnvelope(WORKER_DISPOSE_TYPE, {}),
      });
      this._worker.removeEventListener('message', this._handleMessage);
      this._worker.removeEventListener('error', this._handleError);
      this._worker.removeEventListener('messageerror', this._handleError);
      this._worker.terminate();
    }
    this._worker = null;
    this._messagePort = null;
    this._ready = false;
    this._starting = false;
  }

  private _handleMessage(event: MessageEvent) {
    const message = event.data || {};
    switch (message.type) {
      case 'renderModel':
        this._onRenderModel?.(message.model);
        break;
      case 'emit':
        this._onEmit?.(message);
        break;
      case 'status':
        this._onStatus?.(message);
        break;
      default:
        break;
    }
  }

  private _handleError(event: ErrorEvent | MessageEvent) {
    const maybeErr = event as ErrorEvent;
    const details: string[] = [];
    if (maybeErr.filename) {
      details.push(`file=${maybeErr.filename}`);
    }
    if (maybeErr.lineno || maybeErr.colno) {
      details.push(`line=${maybeErr.lineno || 0}`, `col=${maybeErr.colno || 0}`);
    }

    const message = maybeErr.message || `Worker error while loading ${this._url}`;
    const suffix = details.length ? ` (${details.join(', ')})` : '';
    this._onStatus?.({
      level: 'error',
      message: `${message}${suffix}`,
    });
  }
}

function isPlainObject(value: any): boolean {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

const MERIDIAN_WORKER_COMMANDS = new Set(['init', 'attributeChanged', 'event', 'dispose']);

export function validateWorkerMessage(message: any): string {
  const envelope = parseProtoJsonEnvelope(message?.envelope);

  if (envelope && typeof envelope['@type'] === 'string') {
    const payload = (envelope.payload || {}) as Record<string, any>;
    if (envelope['@type'] === WORKER_INIT_TYPE) {
      message.type = 'init';
      message.payload = payload.payload ?? {};
    } else if (envelope['@type'] === WORKER_ATTRIBUTE_CHANGED_TYPE) {
      message.type = 'attributeChanged';
      message.name = payload.name;
      message.value = payload.value;
    } else if (envelope['@type'] === WORKER_EVENT_TYPE) {
      message.type = 'event';
      message.name = payload.name;
      message.payload = payload.payload;
    } else if (envelope['@type'] === WORKER_DISPOSE_TYPE) {
      message.type = 'dispose';
    }
  }

  if (!isPlainObject(message) || typeof message.type !== 'string') {
    return 'Worker message must be an object with a string `type`.';
  }
  if (!MERIDIAN_WORKER_COMMANDS.has(message.type)) {
    return `Unsupported worker message type: ${message.type}`;
  }
  switch (message.type) {
    case 'init':
      if ('payload' in message && !isPlainObject(message.payload)) {
        return '`init.payload` must be an object when provided.';
      }
      return '';
    case 'attributeChanged':
      if (typeof message.name !== 'string' || !message.name.trim()) {
        return '`attributeChanged.name` must be a non-empty string.';
      }
      return '';
    case 'event':
      if (typeof message.name !== 'string' || !message.name.trim()) {
        return '`event.name` must be a non-empty string.';
      }
      return '';
    case 'dispose':
      return '';
    default:
      return '';
  }
}

function postWorkerMessage(message: any) {
  (self as unknown as Worker).postMessage(message);
}

function emitResult(result: any) {
  if (!result) return;
  if (Object.prototype.hasOwnProperty.call(result, 'model')) {
    postWorkerMessage({ type: 'renderModel', model: result.model });
  }
  if (result.status) {
    postWorkerMessage({ type: 'status', ...result.status });
  }
  if (result.emit) {
    postWorkerMessage({ type: 'emit', ...result.emit });
  }
  if (Array.isArray(result.emits)) {
    for (const entry of result.emits) {
      postWorkerMessage({ type: 'emit', ...entry });
    }
  }
}

export function defineMeridianWorker({
  init,
  update,
  dispose,
}: {
  init?: (payload: any, ctx: any) => Promise<any> | any;
  update?: (state: any, message: any, ctx: any) => Promise<any> | any;
  dispose?: (state: any, ctx: any) => Promise<any> | any;
} = {}) {
  let state: any;
  const ctx = {
    postRenderModel(model: any) {
      postWorkerMessage({ type: 'renderModel', model });
    },
    postStatus(level: string, message: string) {
      postWorkerMessage({ type: 'status', level, message });
    },
    emit(name: string, detail: any = {}) {
      postWorkerMessage({ type: 'emit', name, detail });
    },
  };
  (self as unknown as Worker).addEventListener('message', async (event: MessageEvent) => {
    const message = event.data || {};
    const validationError = validateWorkerMessage(message);
    if (validationError) {
      ctx.postStatus('error', validationError);
      return;
    }

    try {
      if (message.type === 'dispose') {
        await dispose?.(state, ctx);
        (self as unknown as DedicatedWorkerGlobalScope).close();
        return;
      }

      let result = null;
      if (message.type === 'init') {
        result = await init?.(message.payload || {}, ctx);
      } else {
        result = await update?.(state, message, ctx);
      }

      if (result && Object.prototype.hasOwnProperty.call(result, 'state')) {
        state = result.state;
      }
      emitResult(result);
    } catch (error: any) {
      ctx.postStatus('error', error?.message || String(error));
    }
  });
}
