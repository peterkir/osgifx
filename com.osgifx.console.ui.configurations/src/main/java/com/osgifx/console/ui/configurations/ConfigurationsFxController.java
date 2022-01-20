/*******************************************************************************
 * Copyright 2022 Amit Kumar Mondal
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package com.osgifx.console.ui.configurations;

import static org.osgi.namespace.service.ServiceNamespace.SERVICE_NAMESPACE;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.controlsfx.control.table.TableFilter;
import org.controlsfx.control.table.TableRowExpanderColumn;
import org.controlsfx.control.table.TableRowExpanderColumn.TableRowDataFeatures;
import org.eclipse.fx.core.di.LocalInstance;
import org.eclipse.fx.core.log.FluentLogger;
import org.eclipse.fx.core.log.Log;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.framework.BundleContext;

import com.osgifx.console.agent.dto.XConfigurationDTO;
import com.osgifx.console.data.provider.DataProvider;
import com.osgifx.console.util.fx.DTOCellValueFactory;
import com.osgifx.console.util.fx.Fx;

import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;

@Requirement(effective = "active", namespace = SERVICE_NAMESPACE, filter = "(objectClass=com.osgifx.console.data.provider.DataProvider)")
public final class ConfigurationsFxController {

    @Log
    @Inject
    private FluentLogger                            logger;
    @Inject
    @LocalInstance
    private FXMLLoader                              loader;
    @Inject
    private DataProvider                            dataProvider;
    @FXML
    private TableView<XConfigurationDTO>            table;
    @Inject
    @Named("com.osgifx.console.ui.configurations")
    private BundleContext                           context;
    private TableRowDataFeatures<XConfigurationDTO> selectedConfiguration;

    @FXML
    public void initialize() {
        createControls();
        Fx.disableSelectionModel(table);
        logger.atDebug().log("FXML controller has been initialized");
    }

    private void createControls() {
        final BorderPane                                expandedNode   = (BorderPane) Fx.loadFXML(loader, context,
                "/fxml/expander-column-content.fxml");
        final ConfigurationEditorFxController           controller     = loader.getController();
        final TableRowExpanderColumn<XConfigurationDTO> expanderColumn = new TableRowExpanderColumn<>(expandedConfig -> {
                                                                           controller.initControls(expandedConfig.getValue());
                                                                           if (selectedConfiguration != null
                                                                                   && selectedConfiguration.isExpanded()) {
                                                                               selectedConfiguration.toggleExpanded();
                                                                           }
                                                                           selectedConfiguration = expandedConfig;
                                                                           return expandedNode;
                                                                       });

        final TableColumn<XConfigurationDTO, String> pidColumn = new TableColumn<>("PID");
        pidColumn.setPrefWidth(480);
        pidColumn.setCellValueFactory(
                new DTOCellValueFactory<>("pid", String.class, s -> "Not created yet but property descriptor available"));

        final TableColumn<XConfigurationDTO, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setPrefWidth(400);
        nameColumn.setCellValueFactory(new DTOCellValueFactory<>("name", String.class,
                s -> Optional.ofNullable(s.ocd).map(v -> v.name).orElse("No property descriptor available")));

        final TableColumn<XConfigurationDTO, String> locationColumn = new TableColumn<>("Location");
        locationColumn.setPrefWidth(150);
        locationColumn.setCellValueFactory(new DTOCellValueFactory<>("location", String.class, s -> "No bound location"));

        final TableColumn<XConfigurationDTO, String> isFactoryColumn = new TableColumn<>("Is Factory?");
        isFactoryColumn.setPrefWidth(100);
        isFactoryColumn.setCellValueFactory(new DTOCellValueFactory<>("isFactory", String.class));

        table.getColumns().add(expanderColumn);
        table.getColumns().add(pidColumn);
        table.getColumns().add(nameColumn);
        table.getColumns().add(locationColumn);
        table.getColumns().add(isFactoryColumn);

        final ObservableList<XConfigurationDTO> configurations = dataProvider.configurations();
        table.setItems(configurations);
        Fx.sortBy(table, pidColumn);

        TableFilter.forTableView(table).apply();
    }

}