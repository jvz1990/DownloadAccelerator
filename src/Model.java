import com.sun.istack.internal.Nullable;

import java.io.Serializable;
import java.net.URL;
import java.util.ArrayDeque;

public final class Model {
    public static final String EM1 = "1em";
    public static final String ERROR = "error";
    private static final ArrayDeque<DownloadSave> downloadSaves = new ArrayDeque<>();

    public static class DownloadSave implements Serializable {
        private static final long serialVersionUID = -36788054537589577L;
        private URL url;
        private long fileSize;
        private ArrayDeque<String> chunksToDownload;
        private long totalWritten;
        private String destination;
        private String date;
        private String fileName;
        private boolean completed;

        public DownloadSave(URL url, long fileSize, @Nullable ArrayDeque<String> chunksToDownload,
                            long totalWritten, String destination, String date, String fileName, boolean completed) {
            this.url = url;
            this.fileSize = fileSize;
            this.chunksToDownload = chunksToDownload;
            this.totalWritten = totalWritten;
            this.destination = destination;
            this.date = date;
            this.fileName = fileName;
            this.completed = completed;
        }

        public URL getUrl() {
            return url;
        }

        public long getFileSize() {
            return fileSize;
        }

        public ArrayDeque<String> getChunksToDownload() {
            return chunksToDownload;
        }

        public long getTotalWritten() {
            return totalWritten;
        }

        public String getDestination() {
            return destination;
        }

        public String getDate() {
            return date;
        }

        public String getFileName() {
            return fileName;
        }

        public boolean isCompleted() {
            return completed;
        }
    }

    public static void addToDownloads(DownloadSave downloadSave) {
        downloadSaves.add(downloadSave);
    }

    public static ArrayDeque<DownloadSave> getDownloadSaves() {
        return downloadSaves;
    }
}
