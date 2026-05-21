// Meridian web-renderer demo.
//
// Loads the wasm core from the locally-built wasm-pack output (run
// `wasm-pack build rust/uiview --features wasm` first to produce it).
// Mocks the RPC layer with canned JSON responses for the Claims +
// Review tickets panels, matching what the Rust TUI demo does.
//
// To run: any static file server rooted at the meridian repo root
// works (e.g. `python3 -m http.server` from meridian/), then open
// http://localhost:8000/examples/uiview-demo/

import init, {
  renderTable,
  buildPopulateRequest,
  readPath,
} from '../../rust/uiview/pkg/meridian_uiview.js';

import { renderPanel } from '../../src/uiview/renderer.js';

// Hand-built PanelDescriptor JSON. Same shape proto3-JSON would
// produce from the wire form.
const PANELS = [
  {
    panel_id: 'claims',
    title: 'Claims',
    table: {
      populate: {
        service: 'pinax.claims.v1.ClaimsService',
        method: 'ListClaims',
        bindings: [
          { request_field: 'pdf_path', context: 1 /* CURRENT_RESOURCE_PATH */ },
        ],
      },
      rows_field: 'claims',
      item_noun: 'claims',
      placeholder: 'No claims.',
      columns: [
        { header: 'confidence', field_path: 'confidence', format: 2 /* FLOAT_2DP */, pref_width: 80 },
        { header: 'category',   field_path: 'category',   format: 1 /* STRING */,    pref_width: 100 },
        { header: 'claim',      field_path: 'text',       format: 1 /* STRING */ },
        { header: 'entities',   field_path: 'entity_names', format: 5 /* STRING_LIST */, pref_width: 180 },
      ],
    },
  },
  {
    panel_id: 'review_tickets',
    title: 'Review tickets',
    table: {
      populate: {
        service: 'pinax.review.v1.ReviewService',
        method: 'ListReviewTickets',
        bindings: [
          { request_field: 'pdf_path', context: 1 },
        ],
      },
      rows_field: 'review_tickets',
      item_noun: 'tickets',
      placeholder: 'No tickets.',
      columns: [
        { header: 'state',     field_path: 'state',                       format: 1, pref_width: 80  },
        { header: 'ticket_id', field_path: 'ticket_id',                   format: 1, pref_width: 180 },
        { header: 'rationale', field_path: 'rationale',                   format: 1, pref_width: 260 },
        { header: 'subject',   field_path: 'subject.claim.claim_text',   format: 1 },
      ],
    },
  },
  {
    panel_id: 'sparql',
    title: 'SPARQL',
    adhoc: { handler_id: 'sparql' },
  },
];

const mockData = {
  'pinax.claims.v1.ClaimsService/ListClaims': () => ({
    claims: [
      { confidence: 0.95, category: 'descriptive', text: 'A fast-setting concrete repair material with rapid strength gain.', entity_names: ['CEMENT ALL', 'Hydraulic Cement'] },
      { confidence: 0.90, category: 'quantitative', text: 'Achieves structural strength in one hour.', entity_names: ['CEMENT ALL'] },
      { confidence: 0.85, category: 'qualitative', text: 'Designed for vertical and horizontal trowel applications.', entity_names: [] },
    ],
  }),
  'pinax.review.v1.ReviewService/ListReviewTickets': () => ({
    review_tickets: [
      { state: 'OPEN', ticket_id: 'ticket/abc123', rationale: 'claim confidence 0.55 below 0.70 threshold',
        subject: { claim: { claim_text: 'A low-confidence claim X' } } },
      { state: 'RESOLVED', ticket_id: 'ticket/def456', rationale: 'claim confidence 0.60 below 0.70 threshold',
        subject: { claim: { claim_text: 'A claim that was already approved' } } },
    ],
  }),
};

const invoker = {
  invoke(service, method, _request) {
    const key = `${service}/${method}`;
    const fn = mockData[key];
    if (!fn) {
      return Promise.reject(new Error(`unknown method ${key}`));
    }
    return Promise.resolve(fn());
  },
};

const adhocFactories = {
  sparql: (root) => {
    root.style.fontStyle = 'normal';
    root.style.color = '#222';
    root.innerHTML = `
      <p style="color:#666;font-size:13px">
        Adhoc panel: SPARQL editor. Hosts plug bespoke layouts in via
        the <code>adhocFactories</code> registry — the descriptor
        framework just routes the panel_id; the rendering is
        application-specific.
      </p>
      <textarea rows="6" style="width:100%;font-family:Menlo,monospace;font-size:12px"
                placeholder="SELECT ?s WHERE { ?s ?p ?o } LIMIT 25"></textarea>
    `;
  },
};

const context = {
  currentResourcePath: '/demo/mocked.pdf',
  uiIdentity: null,
  selectedRow: null,
  formValues: {},
};

async function main() {
  await init();
  const wasm = { renderTable, buildPopulateRequest, readPath };

  const tabsEl = document.getElementById('tabs');
  const panelEl = document.getElementById('panel');
  let active = 0;

  function paintTabs() {
    tabsEl.innerHTML = '';
    PANELS.forEach((p, i) => {
      const tab = document.createElement('div');
      tab.className = 'tab' + (i === active ? ' active' : '');
      tab.textContent = p.title;
      tab.onclick = () => {
        active = i;
        paintTabs();
        draw();
      };
      tabsEl.appendChild(tab);
    });
  }

  async function draw() {
    await renderPanel({
      wasm,
      root: panelEl,
      descriptor: PANELS[active],
      invoker,
      context,
      adhocFactories,
    });
  }

  paintTabs();
  await draw();
}

main().catch((err) => {
  document.getElementById('panel').textContent = 'Init failed: ' + err;
  console.error(err);
});
