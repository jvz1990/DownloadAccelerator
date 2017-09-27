import com.jfoenix.controls.JFXDecorator;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;

import java.io.*;
import java.util.ArrayDeque;

public class Main extends Application {

    public static Stage stage;

    public static void main(String[] args) {
        new Thread(() ->{
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            File file = new File("settings.bin");
            if(file.exists()) {
                try {
                    ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream("settings.bin"));
                    Object object = objectInputStream.readObject();
                    ArrayDeque<Model.DownloadSave> downloadSaves = (ArrayDeque<Model.DownloadSave>) object;
                    downloadSaves.forEach(downloadSave -> Controller.getDownloads().add(new Download(
                            downloadSave.getUrl(),
                            downloadSave.getFileSize(),
                            downloadSave.getChunksToDownload(),
                            downloadSave.getTotalWritten(),
                            downloadSave.getDestination(),
                            downloadSave.getDate(),
                            downloadSave.getFileName()
                    )));
                    objectInputStream.close();
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        stage = primaryStage;
        FXMLLoader fxmlLoader = new FXMLLoader();
        fxmlLoader.setLocation(Main.class.getResource("/fxml/Main.fxml"));
        AnchorPane root = fxmlLoader.load();

        JFXDecorator jfxDecorator = new JFXDecorator(primaryStage, root);
        jfxDecorator.setCustomMaximize(true);

        Scene scene = new Scene(jfxDecorator, 1280, 800);

        final ObservableList<String> sheets = scene.getStylesheets();
        sheets.addAll(
                Main.class.getResource("/css/jfoenix-fonts.css").toExternalForm(),
                Main.class.getResource("/css/jfoenix-design.css").toExternalForm(),
                Main.class.getResource("/css/main.css").toExternalForm()
        );

        primaryStage.setScene(scene);
        primaryStage.show();
        stage.setOnHiding(e -> {
            Controller.stopAllDownloads();
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream("settings.bin"));
                    objectOutputStream.writeObject(Model.getDownloadSaves());
                    objectOutputStream.flush();
                    objectOutputStream.close();


                    System.exit(0);
                } catch (InterruptedException | IOException e1) {
                    e1.printStackTrace();
                }
            }).start();
        });
        Platform.setImplicitExit(false);
    }

}
