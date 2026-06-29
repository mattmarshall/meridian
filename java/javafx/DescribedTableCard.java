package meridian.ui.javafx;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import meridian.ui.UiBusy;
import meridian.ui.UiCard;
import meridian.ui.descriptors.ProtoPaths;
import meridian.ui.descriptors.RequestBuilder;
import meridian.ui.descriptors.RpcRegistry;
import meridian.ui.v1.ColumnFormat;
import meridian.ui.v1.PanelDescriptor;
import meridian.ui.v1.RowAction;
import meridian.ui.v1.RowFilter;
import meridian.ui.v1.TablePanel;

// JavaFX renderer for the TablePanel shape. One DescribedTableCard
// instance per PanelDescriptor — the descriptor carries the populate
// RPC, column definitions, and action button definitions; the host
// supplies the runtime context (current PDF, UI identity).
//
// This class is the proof that the descriptor framework actually
// works end-to-end. A TUI host would write the equivalent class using
// Lanterna primitives, consuming the same PanelDescriptor.
public final class DescribedTableCard extends VBox implements UiCard {
  private final PanelDescriptor descriptor;
  private final TablePanel table;
  private final RpcRegistry registry;
  private final Message uiIdentity;
  private final MeridianTheme theme;

  private final Label header = new Label();
  private final Label meta = new Label();
  private final HBox actionsBar = new HBox(8);
  private final TableView<Row> tableView = new TableView<>();

  private String currentResourcePath;
  private final AtomicReference<Object> latestLoad = new AtomicReference<>();
  // Action button → predicate from the descriptor; consulted on
  // selection to enable / disable.
  private final List<Runnable> selectionChangeListeners = new ArrayList<>();

  public DescribedTableCard(
      PanelDescriptor descriptor,
      RpcRegistry registry,
      Message uiIdentity,
      MeridianTheme theme) {
    if (descriptor.getBodyCase() != PanelDescriptor.BodyCase.TABLE) {
      throw new IllegalArgumentException(
          "DescribedTableCard requires a TablePanel body; got " + descriptor.getBodyCase());
    }
    this.descriptor = descriptor;
    this.table = descriptor.getTable();
    this.registry = registry;
    this.uiIdentity = uiIdentity;
    this.theme = theme;

    header.setText(descriptor.getTitle());
    header.setStyle(theme.headerStyle());
    meta.setStyle(theme.metaStyle());
    setStyle(theme.rootStyle());

    buildColumns();
    buildActions();
    if (!table.getPlaceholder().isEmpty()) {
      tableView.setPlaceholder(new Label(table.getPlaceholder()));
    }

    if (actionsBar.getChildren().isEmpty()) {
      getChildren().setAll(header, meta, tableView);
    } else {
      actionsBar.setPadding(new Insets(4, 0, 4, 0));
      getChildren().setAll(header, meta, actionsBar, tableView);
    }
    setPadding(new Insets(8));
    setSpacing(4);
    VBox.setVgrow(tableView, Priority.ALWAYS);

    tableView.getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, oldRow, newRow) -> selectionChangeListeners.forEach(Runnable::run));
  }

  @Override public String panelId() { return descriptor.getPanelId(); }
  @Override public Node node() { return this; }

  @Override public void onSelected(Object context, Object treeNodeData) {
    this.currentResourcePath = context instanceof String ? (String) context : null;
    if (currentResourcePath == null) {
      meta.setText("No active resource.");
      tableView.getItems().clear();
      return;
    }
    refresh();
  }

  private void refresh() {
    if (currentResourcePath == null) return;
    meta.setText("Loading… (first load may trigger full enrichment; subsequent loads are cached)");
    Object token = new Object();
    latestLoad.set(token);
    UiBusy.acquire();
    new Thread(() -> {
      try {
        Message response = invokePopulate();
        List<Message> protoRows = ProtoPaths.rows(response, table.getRowsField());
        List<Row> rows = new ArrayList<>(protoRows.size());
        for (Message protoRow : protoRows) {
          rows.add(renderRow(protoRow));
        }
        Platform.runLater(() -> {
          if (latestLoad.get() != token) return;
          tableView.setItems(FXCollections.observableArrayList(rows));
          String noun = table.getItemNoun().isEmpty() ? "items" : table.getItemNoun();
          meta.setText(rows.size() + " " + noun);
        });
      } catch (Throwable t) {
        Platform.runLater(() -> {
          if (latestLoad.get() == token) meta.setText("Failed: " + t.getMessage());
        });
      } finally {
        UiBusy.release();
      }
    }, "meridian-ui-" + descriptor.getPanelId()).start();
  }

  private Message invokePopulate() {
    RpcRegistry.ResolvedMethod method = registry.resolve(
        table.getPopulate().getService(), table.getPopulate().getMethod());
    if (method == null) {
      throw new IllegalStateException(
          "Unregistered RPC " + table.getPopulate().getService()
              + "/" + table.getPopulate().getMethod());
    }
    RequestBuilder.Context ctx = new RequestBuilder.Context(
        currentResourcePath,
        uiIdentity,
        null,
        null);
    Message request = RequestBuilder.build(table.getPopulate(), method.requestPrototype, ctx);
    return registry.call(method, request);
  }

  private void buildColumns() {
    int idx = 0;
    for (meridian.ui.v1.TableColumn col : table.getColumnsList()) {
      String colKey = "col" + idx++;
      TableColumn<Row, String> jfx = new TableColumn<>(col.getHeader());
      jfx.setCellValueFactory(cellData -> cellData.getValue().property(colKey));
      if (col.getPrefWidth() > 0) jfx.setPrefWidth(col.getPrefWidth());
      tableView.getColumns().add(jfx);
    }
  }

  private void buildActions() {
    if (table.getActionsCount() == 0) return;
    for (RowAction action : table.getActionsList()) {
      Button btn = new Button(action.getLabel());
      btn.setOnAction(e -> fireAction(action));
      btn.setDisable(true);
      // Enable when a row is selected (and the predicate, if any, matches).
      selectionChangeListeners.add(() -> btn.setDisable(!isActionEnabled(action)));
      actionsBar.getChildren().add(btn);
    }
  }

  private boolean isActionEnabled(RowAction action) {
    Row selected = tableView.getSelectionModel().getSelectedItem();
    if (selected == null) return false;
    if (!action.hasEnabledWhen()) return true;
    RowFilter filter = action.getEnabledWhen();
    Object value = ProtoPaths.get(selected.message, filter.getFieldPath());
    return filter.getEquals().equals(renderValue(value, ColumnFormat.ENUM_NAME));
  }

  private void fireAction(RowAction action) {
    Row selected = tableView.getSelectionModel().getSelectedItem();
    if (selected == null || currentResourcePath == null) return;
    meta.setText("Running " + action.getLabel() + "…");
    UiBusy.acquire();
    new Thread(() -> {
      try {
        RpcRegistry.ResolvedMethod method = registry.resolve(
            action.getRpc().getService(), action.getRpc().getMethod());
        if (method == null) {
          throw new IllegalStateException(
              "Unregistered RPC " + action.getRpc().getService() + "/" + action.getRpc().getMethod());
        }
        RequestBuilder.Context ctx = new RequestBuilder.Context(
            currentResourcePath, uiIdentity, selected.message, null);
        Message request = RequestBuilder.build(action.getRpc(), method.requestPrototype, ctx);
        registry.call(method, request);
        Platform.runLater(() -> {
          if (action.getRefreshOnSuccess() || !action.hasEnabledWhen()) refresh();
          else meta.setText(action.getLabel() + " done.");
        });
      } catch (Throwable t) {
        Platform.runLater(() -> meta.setText(action.getLabel() + " failed: " + t.getMessage()));
      } finally {
        UiBusy.release();
      }
    }, "meridian-ui-action-" + descriptor.getPanelId()).start();
  }

  private Row renderRow(Message message) {
    Row row = new Row(message);
    int idx = 0;
    for (meridian.ui.v1.TableColumn col : table.getColumnsList()) {
      Object value = ProtoPaths.get(message, col.getFieldPath());
      row.set("col" + idx++, renderValue(value, col.getFormat()));
    }
    return row;
  }

  private static String renderValue(Object value, ColumnFormat format) {
    if (value == null) return "";
    switch (format) {
      case FLOAT_2DP:
        if (value instanceof Number) return String.format("%.2f", ((Number) value).doubleValue());
        return value.toString();
      case INTEGER:
        if (value instanceof Number) return Long.toString(((Number) value).longValue());
        return value.toString();
      case ENUM_NAME:
        if (value instanceof EnumValueDescriptor) return ((EnumValueDescriptor) value).getName();
        return value.toString();
      case STRING_LIST:
        if (value instanceof List<?>) {
          List<?> list = (List<?>) value;
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
          }
          return sb.toString();
        }
        return value.toString();
      case TIMESTAMP:
        if (value instanceof Timestamp) {
          Timestamp t = (Timestamp) value;
          return Instant.ofEpochSecond(t.getSeconds(), t.getNanos()).toString();
        }
        return value.toString();
      case STRING:
      case COLUMN_FORMAT_UNSPECIFIED:
      default:
        return value.toString();
    }
  }

  // Backing row for the TableView. Holds the source Message (so row
  // actions and the enabled_when predicate can resolve row.field_path)
  // plus the per-column rendered strings.
  static final class Row {
    final Message message;
    private final java.util.Map<String, SimpleStringProperty> values = new java.util.HashMap<>();

    Row(Message message) { this.message = message; }

    void set(String colKey, String text) {
      values.computeIfAbsent(colKey, k -> new SimpleStringProperty()).set(text);
    }

    SimpleStringProperty property(String colKey) {
      return values.computeIfAbsent(colKey, k -> new SimpleStringProperty(""));
    }
  }
}
