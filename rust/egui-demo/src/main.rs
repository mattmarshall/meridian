//! meridian × egui — a native (immediate-mode) renderer foil to the web/TUI
//! renderers. It renders a `TablePanel` by reusing the SAME `meridian-uiview`
//! core that the ratatui TUI and the wasm web renderer use:
//!
//!   RequestBuilder::build(populate, ctx) -> RpcInvoker::invoke -> render_table
//!
//! Only the widget layer (egui) is new; the proto-walking, request-building, and
//! cell-formatting are shared. This is the "egui-native" candidate (Candidate 2,
//! and the chrome half of the hybrid Candidate 3) from the miniservo↔meridian
//! backend spike — the foil that needs no web engine at all.

use eframe::egui;
use meridian_tui::{RpcError, RpcInvoker};
use meridian_uiview::proto::{ColumnFormat, RpcCall, TableColumn, TablePanel};
use meridian_uiview::{render_table, Context, RenderedRow, RequestBuilder};
use serde_json::{json, Value};

/// Mock transport — the same canned data as `rust/tui-demo`, so the foil renders
/// the identical panel the TUI does.
struct MockInvoker;

impl RpcInvoker for MockInvoker {
    fn invoke(&self, service: &str, method: &str, _request: Value) -> Result<Value, RpcError> {
        match (service, method) {
            ("pinax.claims.v1.ClaimsService", "ListClaims") => Ok(json!({
                "claims": [
                    {"confidence": 0.95, "category": "descriptive",
                     "text": "A fast-setting concrete repair material with rapid strength gain.",
                     "entity_names": ["CEMENT ALL", "Hydraulic Cement"]},
                    {"confidence": 0.90, "category": "quantitative",
                     "text": "Achieves structural strength in one hour.",
                     "entity_names": ["CEMENT ALL"]},
                    {"confidence": 0.85, "category": "qualitative",
                     "text": "Designed for vertical and horizontal trowel applications.",
                     "entity_names": []}
                ]
            })),
            _ => Err(RpcError::UnknownMethod {
                service: service.into(),
                method: method.into(),
            }),
        }
    }
}

fn col(header: &str, path: &str, fmt: ColumnFormat) -> TableColumn {
    TableColumn {
        header: header.into(),
        field_path: path.into(),
        format: fmt as i32,
        pref_width: 0,
    }
}

fn claims_table() -> TablePanel {
    TablePanel {
        populate: Some(RpcCall {
            service: "pinax.claims.v1.ClaimsService".into(),
            method: "ListClaims".into(),
            bindings: vec![],
        }),
        rows_field: "claims".into(),
        item_noun: "claims".into(),
        placeholder: "no claims".into(),
        columns: vec![
            col("confidence", "confidence", ColumnFormat::Float2dp),
            col("category", "category", ColumnFormat::String),
            col("claim", "text", ColumnFormat::String),
            col("entities", "entity_names", ColumnFormat::StringList),
        ],
        actions: vec![],
    }
}

struct App {
    title: String,
    headers: Vec<String>,
    rows: Vec<RenderedRow>,
}

impl App {
    fn new() -> Self {
        let table = claims_table();
        let ctx = Context::default();
        let call = table.populate.as_ref().expect("populate set");

        // The shared meridian-uiview pipeline — identical to the TUI/web path.
        let request = RequestBuilder::build(call, &ctx);
        let response = MockInvoker
            .invoke(&call.service, &call.method, request)
            .expect("mock invoke");
        let rows = render_table(&response, &table);
        let headers = table.columns.iter().map(|c| c.header.clone()).collect();

        // Headless verification: dump the rendered rows so the data pipeline is
        // checkable without a screenshot (the window proves the egui render path).
        eprintln!("[egui-demo] meridian-uiview rendered {} rows:", rows.len());
        for r in &rows {
            eprintln!("  {:?}", r.cells);
        }

        Self {
            title: "Claims".into(),
            headers,
            rows,
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading(format!("meridian × egui — {}", self.title));
            ui.separator();
            egui::Grid::new("panel-table")
                .striped(true)
                .show(ui, |ui| {
                    for h in &self.headers {
                        ui.label(egui::RichText::new(h).strong());
                    }
                    ui.end_row();
                    for row in &self.rows {
                        for cell in &row.cells {
                            ui.label(cell);
                        }
                        ui.end_row();
                    }
                });
        });
    }
}

fn main() -> eframe::Result<()> {
    eframe::run_native(
        "meridian egui demo",
        eframe::NativeOptions::default(),
        Box::new(|_cc| Ok(Box::new(App::new()))),
    )
}
