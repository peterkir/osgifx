package in.bytehue.osgifx.console.core.data.provider;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import in.bytehue.osgifx.console.agent.ConsoleAgent;
import in.bytehue.osgifx.console.agent.dto.XBundleDTO;
import in.bytehue.osgifx.console.agent.dto.XComponentDTO;
import in.bytehue.osgifx.console.agent.dto.XConfigurationDTO;
import in.bytehue.osgifx.console.agent.dto.XEventDTO;
import in.bytehue.osgifx.console.agent.dto.XPropertyDTO;
import in.bytehue.osgifx.console.agent.dto.XServiceDTO;
import in.bytehue.osgifx.console.supervisor.ConsoleSupervisor;
import in.bytehue.osgifx.console.ui.service.DataProvider;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

@Component
public final class RuntimeDataProvider implements DataProvider {

    @Reference
    private ConsoleSupervisor supervisor;

    private final ObservableList<XBundleDTO>        bundles        = FXCollections.observableArrayList();
    private final ObservableList<XServiceDTO>       services       = FXCollections.observableArrayList();
    private final ObservableList<XComponentDTO>     components     = FXCollections.observableArrayList();
    private final ObservableList<XConfigurationDTO> configurations = FXCollections.observableArrayList();
    private final ObservableList<XEventDTO>         events         = FXCollections.observableArrayList();
    private final ObservableList<XPropertyDTO>      properties     = FXCollections.observableArrayList();

    @Override
    public ObservableList<XBundleDTO> bundles() {
        final ConsoleAgent agent = supervisor.getAgent();
        if (agent == null) {
            return FXCollections.emptyObservableList();
        }
        bundles.clear();
        bundles.addAll(agent.getAllBundles());
        return bundles;
    }

    @Override
    public ObservableList<XServiceDTO> services() {
        return services;
    }

    @Override
    public ObservableList<XComponentDTO> components() {
        return components;
    }

    @Override
    public ObservableList<XConfigurationDTO> configurations() {
        return configurations;
    }

    @Override
    public ObservableList<XEventDTO> events() {
        return events;
    }

    @Override
    public ObservableList<XPropertyDTO> properties() {
        return properties;
    }

}
