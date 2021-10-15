package in.bytehue.osgifx.console.ui.events;

import java.util.Date;
import java.util.Map.Entry;

import org.osgi.util.converter.Converter;
import org.osgi.util.converter.Converters;

import in.bytehue.osgifx.console.agent.dto.XEventDTO;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

public final class EventDetailsFxController {

    @FXML
    private Label                                      receivedAtLabel;
    @FXML
    private Label                                      topicLabel;
    @FXML
    private TableView<Entry<String, String>>           propertiesTable;
    @FXML
    private TableColumn<Entry<String, String>, String> propertiesKeyTableColumn;
    @FXML
    private TableColumn<Entry<String, String>, String> propertiesValueTableColumn;
    private Converter                                  converter;

    @FXML
    public void initialize() {
        converter = Converters.standardConverter();
    }

    public void initControls(final XEventDTO event) {
        receivedAtLabel.setText(formateReceivedAt(event.received));
        topicLabel.setText(event.topic);

        propertiesKeyTableColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getKey()));
        propertiesValueTableColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getValue()));
        propertiesTable.setItems(FXCollections.observableArrayList(event.properties.entrySet()));
    }

    private String formateReceivedAt(final long receivedAt) {
        if (receivedAt == 0) {
            return "No received timestamp";
        }
        return converter.convert(receivedAt).to(Date.class).toString();
    }

}
