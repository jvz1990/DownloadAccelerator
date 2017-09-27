import com.jfoenix.controls.*;
import com.jfoenix.controls.datamodels.treetable.RecursiveTreeObject;
import com.jfoenix.validation.RequiredFieldValidator;
import de.jensd.fx.glyphs.GlyphsBuilder;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.List;
import java.util.function.Function;

public class Controller implements Initializable {
    public AnchorPane root;
    public JFXDialog urlDialog;
    public JFXTextArea urlProperties;
    public JFXTextField URLEntryField;
    public StackPane centerPane;
    public JFXSnackbar snackBar;
    public JFXSlider noOfConnectionsSlider;
    public Label fileNameLabel;
    public Label fileSizeLabel;
    public JFXTreeTableColumn<Download, String> nameColumn;
    public JFXTreeTableColumn<Download, String> dateColumn;
    public JFXTreeTableColumn<Download, String> transferColumn;
    public JFXTreeTableColumn<Download, String> aveSpeedColumn;
    public JFXTreeTableColumn<Download, String> statusColumn;
    public JFXTreeTableColumn<Download, String> locationColumn;
    public JFXTreeTableColumn<Download, String> fileSizeColumn;
    public JFXTreeTableColumn<Download, String> etaColumn;
    public JFXTreeTableView<Download> treeTableView;
    public StackPane aContainer;

    public JFXPopup popup;
    static AnchorPane sRoot;

    JFXListView popupList;


    private UrlProperties URLProperties;
    private static final ObservableList<Download> downloads = FXCollections.observableArrayList();
    public static Download selectedDownload;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        sRoot = root;
        try {
            popupList = FXMLLoader.load(getClass().getResource("/fxml/Popup.fxml"));
            popup = new JFXPopup(popupList);

            popupList.setOnMouseClicked(MouseEvent -> {
                Label label = (Label) popupList.getSelectionModel().getSelectedItem();
                switch (label.getId()) {
                    case "openFileLbl":
                        openFile();
                        break;
                    case "openDirectory":
                        openFolder();
                        break;
                    case "removeItem":
                        if (selectedDownload != null) {
                            selectedDownload.setRemoved(true);
                            downloads.remove(selectedDownload);
                        }
                        break;
                    case "removeFile":
                        deleteFile();
                        break;
                    case "pauseContinue":
                        break;
                }

            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        snackBar.registerSnackbarContainer(root);
        RequiredFieldValidator validator = new RequiredFieldValidator();
        validator.setMessage("Input Required");
        validator.setIcon(GlyphsBuilder.create(FontAwesomeIconView.class)
                .glyph(FontAwesomeIcon.WARNING)
                .size(Model.EM1)
                .styleClass(Model.ERROR)
                .build());

        URLEntryField.getValidators().add(validator);
        URLEntryField.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                URLEntryField.validate();
                loadDialog(URLEntryField.getText());
            }
        });

        setupTreeTable();
    }


    private void setupTreeTable() {
        setupCellValueFactory(nameColumn, Download::getFilename);
        setupCellValueFactory(transferColumn, Download::tranferRateProperty);
        setupCellValueFactory(aveSpeedColumn, Download::averageTransferSpeedProperty);
        setupCellValueFactory(fileSizeColumn, Download::fileSizeColumnProperty);
        setupCellValueFactory(dateColumn, Download::dateAccessedProperty);
        setupCellValueFactory(statusColumn, Download::statusSProperty);
        setupCellValueFactory(locationColumn, Download::fileLocationProperty);
        setupCellValueFactory(etaColumn, Download::etaColumnProperty);

        treeTableView.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            switch (event.getButton()) {
                case SECONDARY:
                    if (!treeTableView.getSelectionModel().isEmpty()) {
                        if (treeTableView.getSelectionModel().getSelectedItem().getValue() != null) {
                            aContainer.setLayoutX(event.getSceneX());
                            aContainer.setLayoutY(event.getSceneY() - 33);
                            aContainer.setVisible(true);
                            selectedDownload = treeTableView.getSelectionModel().getSelectedItem().getValue();
                            popupList.getChildrenUnmodifiable().forEach(Node -> {
                                if (Node.getId().equals("pauseContinue")) {
                                    if (selectedDownload.isKeepRunning()) {
                                        ((Label) Node).setText("Pause");
                                    } else {
                                        ((Label) Node).setText("Resume");
                                    }
                                }
                            });

                            popup.show(aContainer);
                        }
                    }
                    break;
            }
        });

        treeTableView.setRoot(new RecursiveTreeItem<>(downloads, RecursiveTreeObject::getChildren));
        treeTableView.setShowRoot(false);

    }

    public void loadURL() {
        if (URLProperties != null) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save as...");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All Files", "*.*"));
            fileChooser.setInitialFileName(URLProperties.getFilename());
            File selectedFile = fileChooser.showSaveDialog(Main.stage);
            if (selectedFile == null) return;
            urlDialog.close();
            downloads.add(
                    new Download(
                            URLProperties.getHeaderfields(),
                            URLProperties.getUrl(),
                            selectedFile,
                            (int) noOfConnectionsSlider.getValue()
                    )
            );
        }
    }

    public void closeURLDialog() {
        urlDialog.close();
    }

    public void dispURLDialog() {
        urlDialog.show(centerPane);
        try {
            loadDialog((String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor));
        } catch (UnsupportedFlavorException | IOException e) {
            e.printStackTrace();
        }
    }

    private void loadDialog(String data) {
        new Thread(() -> {
            String stringBuilderVals = "";
            try {
                if(!Utilities.doesURLExist(new URL(data))) return;
                URL url = new URL(data);
                snackBar.fireEvent(new JFXSnackbar.SnackbarEvent(
                        "Loading URL properties",
                        "CLOSE",
                        3000,
                        false,
                        b -> snackBar.close()
                ));

                HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
                httpURLConnection.setRequestMethod("HEAD");
                // Some websites don't like programmatic access so pretend to be a browser
                httpURLConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 6.0; en-US; rv:1.9.1.2) Gecko/20090729 Firefox/3.5.2 (.NET CLR 3.5.30729)");

                Map<String, List<String>> headers = httpURLConnection.getHeaderFields();
                StringBuilder stringBuilder = new StringBuilder();
                headers.forEach((K, V) -> {
                    stringBuilder.append("Property: ").append(K).append("\n");
                    stringBuilder.append("Values: {\n");
                    V.forEach(S -> stringBuilder.append("\t[").append(S).append("]\n"));
                    stringBuilder.append("}\n");
                });
                stringBuilderVals = stringBuilder.toString();
                String filename = "";
                if (headers.containsKey("Content-Disposition")) {
                    String temp = headers.get("Content-Disposition").get(0);
                    filename = URLDecoder.decode(temp.substring(temp.lastIndexOf("UTF-8") + 7, temp.length()));
                } else {
                    filename = data.substring(data.lastIndexOf("/") + 1, data.length());
                }
                Long fileSize = Long.parseLong(headers.get("Content-Length").get(0));
                String finalFilename = filename;
                Platform.runLater(() -> {
                    URLProperties = new UrlProperties(headers, url, finalFilename);
                    URLEntryField.setText(data);
                    fileNameLabel.setText(finalFilename);
                    fileSizeLabel.setText(String.valueOf(fileSize / 1000000) + "MB");
                    urlProperties.setText(stringBuilder.toString());
                });
            } catch (IOException ignored) {
                ignored.printStackTrace();
                System.out.println("Error caught");
                System.out.println(stringBuilderVals);
            }
        }).start();
    }

    public static void stopAllDownloads() {
        downloads.forEach(download -> download.setKeepRunning(false));
    }

    public void pauseDownload() {
        stopAllDownloads();
    }

    private void openFile() {
        if (selectedDownload != null) {
            try {
                Desktop.getDesktop().open(new File(selectedDownload.fileLocationProperty().get()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void openFolder() {
        if (selectedDownload != null) {
            try {
                String folder = selectedDownload.fileLocationProperty().get();
                folder = folder.substring(0, folder.length() - selectedDownload.getFilename().get().length());
                Desktop.getDesktop().open(new File(folder));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void deleteFile() {
        if (selectedDownload != null) {
            snackBar.fireEvent(new JFXSnackbar.SnackbarEvent(
                    "Cleaning Up",
                    "CLOSE",
                    1000,
                    false,
                    b -> snackBar.close()
            ));
            new Thread(() -> {
                selectedDownload.setRemoved(true);
                selectedDownload.setKeepRunning(false);
                try {
                    Thread.sleep(1000);
                    File file = new File(selectedDownload.fileLocationProperty().get());
                    downloads.remove(selectedDownload);
                    while (!file.delete()) {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private class UrlProperties {
        private final Map<String, List<String>> headerfields;
        private URL url;
        private String filename;

        private UrlProperties(Map<String, List<String>> headerfields, URL url, String filename) {
            this.headerfields = headerfields;
            this.url = url;
            this.filename = filename;
        }

        public Map<String, List<String>> getHeaderfields() {
            return headerfields;
        }

        public URL getUrl() {
            return url;
        }

        public String getFilename() {
            return filename;
        }
    }

    private <T> void setupCellValueFactory(JFXTreeTableColumn<Download, T> column, Function<Download, ObservableValue<T>> mapper) {
        column.setCellValueFactory((TreeTableColumn.CellDataFeatures<Download, T> param) -> {
            if (column.validateValue(param)) {
                return mapper.apply(param.getValue().getValue());
            } else {
                return column.getComputedValue(param);
            }
        });
    }

    public static ObservableList<Download> getDownloads() {
        return downloads;
    }

}
