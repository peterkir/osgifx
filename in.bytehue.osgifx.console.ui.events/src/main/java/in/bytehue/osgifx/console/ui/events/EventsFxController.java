package in.bytehue.osgifx.console.ui.events;

import static org.osgi.namespace.service.ServiceNamespace.SERVICE_NAMESPACE;

import java.util.Date;

import javax.inject.Inject;
import javax.inject.Named;

import org.controlsfx.control.table.TableFilter;
import org.controlsfx.control.table.TableRowExpanderColumn;
import org.eclipse.fx.core.di.LocalInstance;
import org.eclipse.fx.core.log.FluentLogger;
import org.eclipse.fx.core.log.Log;
import org.osgi.annotation.bundle.Capability;
import org.osgi.framework.BundleContext;

import in.bytehue.osgifx.console.agent.dto.XEventDTO;
import in.bytehue.osgifx.console.data.provider.DataProvider;
import in.bytehue.osgifx.console.util.fx.DTOCellValueFactory;
import in.bytehue.osgifx.console.util.fx.Fx;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;

@Capability(namespace = SERVICE_NAMESPACE, attribute = "objectClass:List<String>=in.bytehue.osgifx.console.data.provider.DataProvider")
public final class EventsFxController {

    @Log
    @Inject
    private FluentLogger         logger;
    @Inject
    @LocalInstance
    private FXMLLoader           loader;
    @FXML
    private TableView<XEventDTO> table;
    @Inject
    private DataProvider         dataProvider;
    @Inject
    @Named("in.bytehue.osgifx.console.ui.events")
    private BundleContext        context;

    private TableRowExpanderColumn.TableRowDataFeatures<XEventDTO> selectedEvent;

    @FXML
    public void initialize() {
        createControls();
        Fx.disableSelectionModel(table);
        logger.atDebug().log("FXML controller has been initialized");
    }

    private void createControls() {
        final GridPane                          expandedNode   = (GridPane) Fx.loadFXML(loader, context,
                "/fxml/expander-column-content.fxml");
        final EventDetailsFxController          controller     = loader.getController();
        final TableRowExpanderColumn<XEventDTO> expanderColumn = new TableRowExpanderColumn<>(expandedEvent -> {
                                                                   controller.initControls(expandedEvent.getValue());
                                                                   if (selectedEvent != null && selectedEvent.isExpanded()) {
                                                                       selectedEvent.toggleExpanded();
                                                                   }
                                                                   selectedEvent = expandedEvent;
                                                                   return expandedNode;
                                                               });

        final TableColumn<XEventDTO, Date> receivedAtColumn = new TableColumn<>("Received At");

        receivedAtColumn.setPrefWidth(290);
        receivedAtColumn.setCellValueFactory(new DTOCellValueFactory<>("received", Date.class));

        final TableColumn<XEventDTO, String> topicColumn = new TableColumn<>("Topic");

        topicColumn.setPrefWidth(650);
        topicColumn.setCellValueFactory(new DTOCellValueFactory<>("topic", String.class));

        table.getColumns().add(expanderColumn);
        table.getColumns().add(receivedAtColumn);
        table.getColumns().add(topicColumn);

        final ObservableList<XEventDTO> bundles = dataProvider.events();
        table.setItems(bundles);

        TableFilter.forTableView(table).apply();
    }

}
