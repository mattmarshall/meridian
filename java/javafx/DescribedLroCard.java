package meridian.ui.javafx;

import com.google.longrunning.Operation;
import com.google.longrunning.OperationsGrpc;
import com.google.longrunning.WaitOperationRequest;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import meridian.ui.UiBusy;
import meridian.ui.UiCard;
import meridian.ui.descriptors.ProtoPaths;
import meridian.ui.descriptors.RequestBuilder;
import meridian.ui.descriptors.RpcRegistry;
import meridian.ui.v1.ColumnFormat;
import meridian.ui.v1.FormField;
import meridian.ui.v1.IntegerSpinner;
import meridian.ui.v1.LroPanel;
import meridian.ui.v1.PanelDescriptor;
import meridian.ui.v1.TablePanel;
import meridian.ui.v1.TextInput;

// JavaFX renderer for the LroPanel shape. Drives:
//
//   1. Form inputs (FormField list) populating the start RPC request.
//   2. Submit + poll WaitOperation, streaming metadata into the meta
//      label via Class.forName(metadata_type) + Any.unpack.
//   3. Optionally fire a finalize RPC against the LRO's response.
//   4. Render the final response (or the finalize response) as a
//      table using the embedded TablePanel.result descriptor.
//
// The proto's metadata_type / response_type fields are interpreted as
// Java class names, which is exact because our protos set
// java_multiple_files = true with java_package matching the proto
// package. A TUI renderer reading the same descriptor would resolve
// the type its own way (registry, type_url-based dispatch, etc.).
public final class DescribedLroCard extends VBox implements UiCard {
  private final PanelDescriptor descriptor;
  private final LroPanel panel;
  private final RpcRegistry registry;
  private final Message uiIdentity;
  private final MeridianTheme theme;
  private final OperationsGrpc.OperationsBlockingStub opsStub;

  private final Label header = new Label();
  private final Label meta = new Label();
  private final Button runButton = new Button();
  private final TableView<DescribedTableCard.Row> resultTable = new TableView<>();
  private final HBox formRow = new HBox(8);
  // Form widgets keyed by field_id; values are read at submit time.
  private final Map<String, Supplier<Object>> formValues = new LinkedHashMap<>();

  private String currentResourcePath;
  private Class<? extends Message> metadataClass;
  private Class<? extends Message> responseClass;

  public DescribedLroCard(
      PanelDescriptor descriptor,
      RpcRegistry registry,
      Message uiIdentity,
      ManagedChannel channel,
      MeridianTheme theme) {
    if (descriptor.getBodyCase() != PanelDescriptor.BodyCase.LRO) {
      throw new IllegalArgumentException(
          "DescribedLroCard requires an LroPanel body; got " + descriptor.getBodyCase());
    }
    this.descriptor = descriptor;
    this.panel = descriptor.getLro();
    this.registry = registry;
    this.uiIdentity = uiIdentity;
    this.theme = theme;
    this.opsStub = OperationsGrpc.newBlockingStub(channel);

    header.setText(descriptor.getTitle());
    header.setStyle(theme.headerStyle());
    meta.setStyle(theme.metaStyle());
    setStyle(theme.rootStyle());

    runButton.setText(panel.getRunButtonLabel().isEmpty()
        ? "Run" : panel.getRunButtonLabel());
    runButton.setOnAction(e -> startRun());
    runButton.setDisable(true);

    buildForm();
    buildResultTable();

    formRow.setPadding(new Insets(2, 0, 2, 0));
    HBox actions = new HBox(8, runButton);
    actions.setPadding(new Insets(4, 0, 4, 0));

    if (panel.getInputsCount() > 0) {
      getChildren().setAll(header, meta, formRow, actions, resultTable);
    } else {
      getChildren().setAll(header, meta, actions, resultTable);
    }
    setPadding(new Insets(8));
    setSpacing(4);
    VBox.setVgrow(resultTable, Priority.ALWAYS);

    // Resolve metadata + LRO-response classes once at construction;
    // failure fast-fails before the user clicks the button. The
    // finalize response class (if any) is resolved lazily at finalize
    // time via the registered RpcRegistry method's response Message.
    try {
      metadataClass = Class.forName(panel.getMetadataType()).asSubclass(Message.class);
      responseClass = Class.forName(panel.getResponseType()).asSubclass(Message.class);
    } catch (ClassNotFoundException e) {
      meta.setText("Misconfigured: " + e.getMessage());
      runButton.setDisable(true);
    }
  }

  @Override public String panelId() { return descriptor.getPanelId(); }
  @Override public Node node() { return this; }

  @Override public void onSelected(Object context, Object treeNodeData) {
    this.currentResourcePath = context instanceof String ? (String) context : null;
    if (currentResourcePath == null) {
      meta.setText("No active resource.");
      runButton.setDisable(true);
    } else {
      runButton.setDisable(false);
      if (meta.getText() == null || meta.getText().isEmpty()) {
        meta.setText("Click the button to run.");
      }
    }
  }

  private void buildForm() {
    for (FormField field : panel.getInputsList()) {
      Label label = new Label(field.getLabel() + ":");
      switch (field.getKindCase()) {
        case INTEGER: {
          IntegerSpinner spec = field.getInteger();
          int min = spec.getMin();
          int max = spec.getMax() > 0 ? spec.getMax() : Integer.MAX_VALUE;
          int def = spec.getDefaultValue();
          int step = spec.getStep() > 0 ? spec.getStep() : 1;
          Spinner<Integer> spinner = new Spinner<>(min, max, def, step);
          formValues.put(field.getFieldId(), spinner::getValue);
          formRow.getChildren().add(label);
          formRow.getChildren().add(spinner);
          break;
        }
        case TEXT: {
          TextInput spec = field.getText();
          TextField tf = new TextField(spec.getDefaultValue());
          formValues.put(field.getFieldId(), tf::getText);
          formRow.getChildren().add(label);
          formRow.getChildren().add(tf);
          break;
        }
        case KIND_NOT_SET:
        default:
          break;
      }
    }
  }

  private void buildResultTable() {
    if (!panel.hasResult()) {
      resultTable.setPlaceholder(new Label("(no result table configured)"));
      return;
    }
    TablePanel result = panel.getResult();
    int idx = 0;
    for (meridian.ui.v1.TableColumn col : result.getColumnsList()) {
      String colKey = "col" + idx++;
      TableColumn<DescribedTableCard.Row, String> jfx = new TableColumn<>(col.getHeader());
      jfx.setCellValueFactory(cd -> cd.getValue().property(colKey));
      if (col.getPrefWidth() > 0) jfx.setPrefWidth(col.getPrefWidth());
      resultTable.getColumns().add(jfx);
    }
    if (!result.getPlaceholder().isEmpty()) {
      resultTable.setPlaceholder(new Label(result.getPlaceholder()));
    }
  }

  private void startRun() {
    if (currentResourcePath == null) return;
    runButton.setDisable(true);
    meta.setText("Submitting…");
    UiBusy.acquire();
    Map<String, Object> formSnapshot = snapshotForm();
    new Thread(() -> {
      try {
        drive(formSnapshot);
      } finally {
        UiBusy.release();
      }
    }, "meridian-ui-" + descriptor.getPanelId()).start();
  }

  private Map<String, Object> snapshotForm() {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<String, Supplier<Object>> e : formValues.entrySet()) {
      values.put(e.getKey(), e.getValue().get());
    }
    return values;
  }

  private void drive(Map<String, Object> formSnapshot) {
    try {
      RpcRegistry.ResolvedMethod startMethod = registry.resolve(
          panel.getStart().getService(), panel.getStart().getMethod());
      if (startMethod == null) {
        throw new IllegalStateException("Unregistered start RPC "
            + panel.getStart().getService() + "/" + panel.getStart().getMethod());
      }
      RequestBuilder.Context ctx = new RequestBuilder.Context(
          currentResourcePath, uiIdentity, null, formSnapshot);
      Message request = RequestBuilder.build(
          panel.getStart(), startMethod.requestPrototype, ctx);
      Operation op = (Operation) registry.call(startMethod, request);

      long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
      while (!op.getDone() && System.nanoTime() < deadline) {
        // Short server-side wait so the UI metadata stream is live.
        // WaitOperation returns early on completion so this doesn't
        // penalize fast operations.
        op = opsStub.waitOperation(WaitOperationRequest.newBuilder()
            .setName(op.getName())
            .setTimeout(Duration.newBuilder().setSeconds(5).build())
            .build());
        if (op.hasMetadata() && metadataClass != null) {
          try {
            Message md = op.getMetadata().unpack(metadataClass);
            String status = renderMetadata(md);
            Platform.runLater(() -> meta.setText(status));
          } catch (InvalidProtocolBufferException ignored) {}
        }
      }
      if (!op.getDone()) {
        Platform.runLater(() -> {
          meta.setText("Timed out before completion");
          runButton.setDisable(false);
        });
        return;
      }
      if (op.hasError()) {
        String msg = op.getError().getMessage();
        Platform.runLater(() -> {
          meta.setText("Failed: " + msg);
          runButton.setDisable(false);
        });
        return;
      }

      // Source data for the result table: finalize response if set,
      // otherwise the LRO response itself.
      Message source;
      if (panel.hasFinalize()) {
        RpcRegistry.ResolvedMethod finalizeMethod = registry.resolve(
            panel.getFinalize().getService(), panel.getFinalize().getMethod());
        if (finalizeMethod == null) {
          throw new IllegalStateException("Unregistered finalize RPC "
              + panel.getFinalize().getService() + "/" + panel.getFinalize().getMethod());
        }
        Message lroResponse = op.getResponse().unpack(responseClass);
        Platform.runLater(() -> meta.setText("Running " + panel.getFinalize().getMethod() + "…"));
        Message finalizeRequest = RequestBuilder.build(
            panel.getFinalize(),
            finalizeMethod.requestPrototype,
            new RequestBuilder.Context(
                currentResourcePath, uiIdentity, lroResponse, formSnapshot));
        source = registry.call(finalizeMethod, finalizeRequest);
      } else {
        source = op.getResponse().unpack(responseClass);
      }

      final Message finalSource = source;
      Platform.runLater(() -> {
        renderResult(finalSource);
        runButton.setDisable(false);
      });
    } catch (Throwable t) {
      Platform.runLater(() -> {
        meta.setText("Failed: " + t.getMessage());
        runButton.setDisable(false);
      });
    }
  }

  private String renderMetadata(Message metadata) {
    // Convention: render "[state] status_message" if those fields exist.
    StringBuilder sb = new StringBuilder();
    Object state = ProtoPaths.get(metadata, "state");
    if (state instanceof EnumValueDescriptor) {
      sb.append("[").append(((EnumValueDescriptor) state).getName()).append("] ");
    }
    Object status = ProtoPaths.get(metadata, "status_message");
    if (status != null) sb.append(status);
    if (sb.length() == 0) sb.append(metadata.toString());
    return sb.toString();
  }

  private void renderResult(Message source) {
    if (!panel.hasResult()) {
      meta.setText("Done.");
      return;
    }
    TablePanel result = panel.getResult();
    List<Message> protoRows = ProtoPaths.rows(source, result.getRowsField());
    java.util.List<DescribedTableCard.Row> rows = new java.util.ArrayList<>(protoRows.size());
    for (Message protoRow : protoRows) {
      DescribedTableCard.Row row = new DescribedTableCard.Row(protoRow);
      int idx = 0;
      for (meridian.ui.v1.TableColumn col : result.getColumnsList()) {
        Object value = ProtoPaths.get(protoRow, col.getFieldPath());
        row.set("col" + idx++, renderValue(value, col.getFormat()));
      }
      rows.add(row);
    }
    resultTable.setItems(FXCollections.observableArrayList(rows));
    String noun = result.getItemNoun().isEmpty() ? "rows" : result.getItemNoun();
    meta.setText("Done · " + rows.size() + " " + noun);
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

  @FunctionalInterface
  private interface Supplier<T> { T get(); }
}
