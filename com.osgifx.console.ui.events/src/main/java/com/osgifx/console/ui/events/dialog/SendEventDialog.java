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
package com.osgifx.console.ui.events.dialog;

import static com.osgifx.console.constants.FxConstants.STANDARD_CSS;

import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;

import org.controlsfx.control.ToggleSwitch;
import org.controlsfx.control.textfield.CustomTextField;
import org.controlsfx.control.textfield.TextFields;
import org.controlsfx.dialog.LoginDialog;
import org.controlsfx.glyphfont.FontAwesome;
import org.controlsfx.glyphfont.Glyph;
import org.eclipse.fx.core.Triple;
import org.eclipse.fx.core.log.FluentLogger;
import org.eclipse.fx.core.log.Log;

import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.osgifx.console.ui.events.converter.ValueConverter;
import com.osgifx.console.ui.events.converter.ValueType;
import com.osgifx.console.util.fx.FxDialog;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;

public final class SendEventDialog extends Dialog<EventDTO> {

    @Log
    @Inject
    private FluentLogger   logger;
    @Inject
    private ValueConverter converter;

    private final Map<PropertiesForm, Triple<Supplier<String>, Supplier<String>, Supplier<ValueType>>> entries = Maps.newHashMap();

    public void init() {
        final DialogPane dialogPane = getDialogPane();

        initStyle(StageStyle.UNDECORATED);
        dialogPane.setHeaderText("Send OSGi Events");
        dialogPane.getStylesheets().add(LoginDialog.class.getResource("dialogs.css").toExternalForm());
        dialogPane.getStylesheets().add(getClass().getResource(STANDARD_CSS).toExternalForm());
        dialogPane.setGraphic(new ImageView(this.getClass().getResource("/graphic/images/events.png").toString()));
        dialogPane.getButtonTypes().addAll(ButtonType.CANCEL);

        final CustomTextField txtTopic = (CustomTextField) TextFields.createClearableTextField();
        txtTopic.setLeft(new ImageView(getClass().getResource("/graphic/icons/id.png").toExternalForm()));

        final ToggleSwitch isSyncToggle = new ToggleSwitch("Is Synchronous?");

        final Label lbMessage = new Label("");
        lbMessage.getStyleClass().addAll("message-banner");
        lbMessage.setVisible(false);
        lbMessage.setManaged(false);

        final VBox content = new VBox(10);

        content.getChildren().add(lbMessage);
        content.getChildren().add(isSyncToggle);
        content.getChildren().add(txtTopic);
        addFieldPair(content);

        dialogPane.setContent(content);

        final ButtonType createButtonType = new ButtonType("Send", ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(createButtonType);

        final Button loginButton = (Button) dialogPane.lookupButton(createButtonType);
        loginButton.setOnAction(actionEvent -> {
            try {
                lbMessage.setVisible(false);
                lbMessage.setManaged(false);
                hide();
            } catch (final Exception ex) {
                lbMessage.setVisible(true);
                lbMessage.setManaged(true);
                lbMessage.setText(ex.getMessage());
                FxDialog.showExceptionDialog(ex, getClass().getClassLoader());
            }
        });
        final String pidCaption = "Topic";

        txtTopic.setPromptText(pidCaption);

        setResultConverter(dialogButton -> {
            final ButtonData data = dialogButton == null ? null : dialogButton.getButtonData();
            try {
                return data == ButtonData.OK_DONE ? getInput(txtTopic, isSyncToggle) : null;
            } catch (final Exception e) {
                logger.atError().withException(e).log("Configuration values cannot be converted");
            }
            return null;
        });
    }

    private EventDTO getInput(final CustomTextField txtTopic, final ToggleSwitch isSyncToggle) throws Exception {
        final EventDTO config = new EventDTO();

        config.topic  = txtTopic.getText();
        config.isSync = isSyncToggle.isSelected();

        final Map<String, Object> properties = Maps.newHashMap();
        for (final Entry<PropertiesForm, Triple<Supplier<String>, Supplier<String>, Supplier<ValueType>>> entry : entries.entrySet()) {
            final Triple<Supplier<String>, Supplier<String>, Supplier<ValueType>> value       = entry.getValue();
            final String                                                          configKey   = value.value1.get();
            final String                                                          configValue = value.value2.get();
            ValueType                                                             configType  = value.value3.get();
            if (Strings.isNullOrEmpty(configKey) || Strings.isNullOrEmpty(configValue)) {
                continue;
            }
            if (configType == null) {
                configType = ValueType.STRING;
            }
            final Object convertedValue = converter.convert(configValue, configType);
            properties.put(configKey, convertedValue);
        }
        config.properties = properties;
        return config;
    }

    private class PropertiesForm extends HBox {

        private final CustomTextField txtKey;
        private final CustomTextField txtValue;

        private final Button btnAddField;
        private final Button btnRemoveField;

        public PropertiesForm(final VBox parent) {
            setAlignment(Pos.CENTER_LEFT);
            setSpacing(5);

            final String keyCaption   = "Key";
            final String valueCaption = "Value";

            txtKey = (CustomTextField) TextFields.createClearableTextField();
            txtKey.setLeft(new ImageView(getClass().getResource("/graphic/icons/kv.png").toExternalForm()));

            txtValue = (CustomTextField) TextFields.createClearableTextField();
            txtValue.setLeft(new ImageView(getClass().getResource("/graphic/icons/kv.png").toExternalForm()));

            txtKey.setPromptText(keyCaption);
            txtValue.setPromptText(valueCaption);

            btnAddField    = new Button();
            btnRemoveField = new Button();

            final ObservableList<ValueType> options  = FXCollections.observableArrayList(ValueType.values());
            final ComboBox<ValueType>       comboBox = new ComboBox<>(options);

            comboBox.getSelectionModel().select(0); // default STRING type

            btnAddField.setGraphic(new Glyph("FontAwesome", FontAwesome.Glyph.PLUS));
            btnRemoveField.setGraphic(new Glyph("FontAwesome", FontAwesome.Glyph.MINUS));

            btnAddField.setOnAction(e -> addFieldPair(parent));
            btnRemoveField.setOnAction(e -> removeFieldPair(parent, this));

            getChildren().addAll(txtKey, txtValue, comboBox, btnAddField, btnRemoveField);

            final Triple<Supplier<String>, Supplier<String>, Supplier<ValueType>> tuple = new Triple<>(txtKey::getText, txtValue::getText,
                    comboBox::getValue);
            entries.put(this, tuple);
        }
    }

    private void addFieldPair(final VBox content) {
        content.getChildren().add(new PropertiesForm(content));
        getDialogPane().getScene().getWindow().sizeToScene();
    }

    private void removeFieldPair(final VBox content, final PropertiesForm form) {
        if (content.getChildren().size() > 4) {
            content.getChildren().remove(form);
            getDialogPane().getScene().getWindow().sizeToScene();
        }
        entries.remove(form);
    }

}