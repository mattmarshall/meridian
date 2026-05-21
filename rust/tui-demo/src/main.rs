// Standalone TUI demo. Renders Pinax-style Claims / Review tickets /
// SPARQL panels using meridian-uiview + meridian-tui, with a
// `MockInvoker` returning canned JSON instead of talking to a real
// gRPC server. A real host swaps in a tonic-backed RpcInvoker; the
// rest of the renderer is identical.
//
// Run: `cargo run -p meridian-tui-demo`
// Keys: q to quit, Tab/Shift-Tab to cycle panels, ↑/↓ to move row.

use crossterm::event::{self, Event, KeyCode, KeyEventKind, KeyModifiers};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use meridian_tui::{PanelAppState, PanelView, RpcError, RpcInvoker};
use meridian_uiview::proto::{
    field_binding, panel_descriptor, AdhocPanel, ColumnFormat, ContextSource, FieldBinding,
    PanelDescriptor, RpcCall, TableColumn, TablePanel,
};
use meridian_uiview::Context;
use ratatui::backend::CrosstermBackend;
use ratatui::layout::{Constraint, Layout};
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Terminal;
use serde_json::{json, Value};
use std::io;
use std::io::stdout;

// Mocked invoker — returns canned data for known (service, method)
// pairs. A production host would back this with a tonic client.
struct MockInvoker;

impl RpcInvoker for MockInvoker {
    fn invoke(
        &self,
        service: &str,
        method: &str,
        _request: Value,
    ) -> Result<Value, RpcError> {
        match (service, method) {
            ("pinax.claims.v1.ClaimsService", "ListClaims") => Ok(json!({
                "claims": [
                    {
                        "confidence": 0.95,
                        "category": "descriptive",
                        "text": "A fast-setting concrete repair material with rapid strength gain.",
                        "entity_names": ["CEMENT ALL", "Hydraulic Cement"]
                    },
                    {
                        "confidence": 0.90,
                        "category": "quantitative",
                        "text": "Achieves structural strength in one hour.",
                        "entity_names": ["CEMENT ALL"]
                    },
                    {
                        "confidence": 0.85,
                        "category": "qualitative",
                        "text": "Designed for vertical and horizontal trowel applications.",
                        "entity_names": []
                    }
                ]
            })),
            ("pinax.review.v1.ReviewService", "ListReviewTickets") => Ok(json!({
                "review_tickets": [
                    {"state": "OPEN", "ticket_id": "ticket/abc123",
                     "rationale": "claim confidence 0.55 below 0.70 threshold",
                     "subject": {"claim": {"claim_text": "Low-confidence claim X"}}},
                    {"state": "RESOLVED", "ticket_id": "ticket/def456",
                     "rationale": "claim confidence 0.60 below 0.70 threshold",
                     "subject": {"claim": {"claim_text": "Already-approved claim Y"}}}
                ]
            })),
            _ => Err(RpcError::UnknownMethod {
                service: service.to_string(),
                method: method.to_string(),
            }),
        }
    }
}

fn build_panels() -> Vec<PanelDescriptor> {
    let claims = PanelDescriptor {
        panel_id: "claims".into(),
        title: "Claims".into(),
        body: Some(panel_descriptor::Body::Table(TablePanel {
            populate: Some(populate(
                "pinax.claims.v1.ClaimsService",
                "ListClaims",
            )),
            rows_field: "claims".into(),
            item_noun: "claims".into(),
            placeholder: "no claims".into(),
            columns: vec![
                col("confidence", "confidence", ColumnFormat::Float2dp, 12),
                col("category", "category", ColumnFormat::String, 14),
                col("claim", "text", ColumnFormat::String, 60),
                col("entities", "entity_names", ColumnFormat::StringList, 30),
            ],
            actions: vec![],
        })),
    };

    let tickets = PanelDescriptor {
        panel_id: "review_tickets".into(),
        title: "Review tickets".into(),
        body: Some(panel_descriptor::Body::Table(TablePanel {
            populate: Some(populate(
                "pinax.review.v1.ReviewService",
                "ListReviewTickets",
            )),
            rows_field: "review_tickets".into(),
            item_noun: "tickets".into(),
            placeholder: "no tickets".into(),
            columns: vec![
                col("state", "state", ColumnFormat::String, 10),
                col("id", "ticket_id", ColumnFormat::String, 22),
                col("rationale", "rationale", ColumnFormat::String, 40),
                col("subject", "subject.claim.claim_text", ColumnFormat::String, 50),
            ],
            actions: vec![],
        })),
    };

    let sparql = PanelDescriptor {
        panel_id: "sparql".into(),
        title: "SPARQL".into(),
        body: Some(panel_descriptor::Body::Adhoc(AdhocPanel {
            handler_id: "sparql".into(),
        })),
    };

    vec![claims, tickets, sparql]
}

fn populate(service: &str, method: &str) -> RpcCall {
    RpcCall {
        service: service.into(),
        method: method.into(),
        bindings: vec![FieldBinding {
            request_field: "pdf_path".into(),
            source: Some(field_binding::Source::Context(
                ContextSource::CurrentResourcePath as i32,
            )),
        }],
    }
}

fn col(header: &str, path: &str, fmt: ColumnFormat, width: i32) -> TableColumn {
    TableColumn {
        header: header.into(),
        field_path: path.into(),
        format: fmt as i32,
        pref_width: width,
    }
}

fn main() -> io::Result<()> {
    let panels = build_panels();
    let mut context = Context::default();
    context.current_resource_path = Some("/demo/mocked.pdf".into());

    let mut app = PanelAppState::new(panels, context, MockInvoker);
    let mut view = PanelView::new();

    enable_raw_mode()?;
    execute!(stdout(), EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout());
    let mut terminal = Terminal::new(backend)?;

    loop {
        terminal.draw(|f| {
            let chunks = Layout::vertical([Constraint::Length(1), Constraint::Min(1)])
                .split(f.area());
            f.render_widget(render_tabs(&app), chunks[0]);
            if let Some(panel) = app.active_panel() {
                view.render(f, chunks[1], panel, &app.context, &app.invoker);
            }
        })?;

        if event::poll(std::time::Duration::from_millis(150))? {
            if let Event::Key(key) = event::read()? {
                if key.kind != KeyEventKind::Press {
                    continue;
                }
                match key.code {
                    KeyCode::Char('q') => break,
                    KeyCode::Tab => {
                        app.next_panel();
                        view.invalidate();
                    }
                    KeyCode::BackTab => {
                        app.prev_panel();
                        view.invalidate();
                    }
                    KeyCode::Down => view.select_next(),
                    KeyCode::Up => view.select_prev(),
                    KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => break,
                    _ => {}
                }
            }
        }
    }

    disable_raw_mode()?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen)?;
    Ok(())
}

fn render_tabs<I: RpcInvoker>(app: &PanelAppState<I>) -> Paragraph<'_> {
    let mut spans = Vec::new();
    for (i, panel) in app.panels.iter().enumerate() {
        let style = if i == app.active {
            Style::default().add_modifier(Modifier::REVERSED)
        } else {
            Style::default()
        };
        spans.push(Span::styled(format!(" {} ", panel.title), style));
        spans.push(Span::raw(" "));
    }
    spans.push(Span::styled(
        "  [Tab] next  [Shift-Tab] prev  [↑/↓] row  [q] quit",
        Style::default().fg(ratatui::style::Color::DarkGray),
    ));
    Paragraph::new(Line::from(spans))
}
