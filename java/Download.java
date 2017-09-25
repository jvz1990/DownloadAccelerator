
import com.jfoenix.controls.datamodels.treetable.RecursiveTreeObject;
import com.sun.istack.internal.Nullable;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.LinkedTransferQueue;

class Download extends RecursiveTreeObject<Download> {
    private StringProperty filename;
    private StringProperty dateAccessed;
    private StringProperty tranferRate;
    private StringProperty statusS;
    private StringProperty fileLocation;
    private StringProperty averageTransferSpeed;
    private StringProperty fileSizeColumn;
    private StringProperty etaColumn;

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    private long fileSize;
    private URL url;
    private final LinkedTransferQueue<Chunk> chunksToBeWritten = new LinkedTransferQueue<>();
    private final ArrayDeque<SectionDownload> sectionDownloads = new ArrayDeque<>();
    private RandomAccessFile randomAccessFile;
    private long totalDataWrote;
    private long totalDataWrotePrev;
    private long totalWroteToFile;
    private Timer timer;
    private boolean keepRunning = true, added = false;
    private double N = 0;
    private double aveSpeed = 0;

    private static final DecimalFormat numberFormat = new DecimalFormat("#.00");

    private static final int BUFFER_SIZE = 1024 * 1024; //1MB
    private static final int START_TO_WRITE = BUFFER_SIZE * 7; // Start to write at 7MB
    private static final int MAX_BUFFER_SIZE = BUFFER_SIZE * 10; // 10MB
    private static final double KILO = 1024.0;
    private static final double MEGA = KILO * 1024.0;
    private static final double GIGA = MEGA * 1024.0;

    private static final double MIN = 60;
    private static final double HOUR = MIN * 60;
    private static final double DAY = HOUR * 24;

    private boolean removed = false;


    private int downloadSplits = 4;

    private Runnable writer = new Runnable() {
        @Override
        public void run() {
            while (keepRunning) {
                try {
                    Chunk chunk = chunksToBeWritten.take();
                    randomAccessFile.seek(chunk.getStartPosition());
                    randomAccessFile.write(chunk.getData());
                    totalWroteToFile += chunk.getData().length;
                    chunk.deReference();
                    chunk = null;
                } catch (InterruptedException | IOException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private TimerTask timerTask = new TimerTask() {
        @Override
        public void run() {
            long diff = totalDataWrote - totalDataWrotePrev;
            totalDataWrotePrev = totalDataWrote;
            N++;
            aveSpeed = approxRollingAverage(aveSpeed, diff);
            double secondsToGo = (totalDataWrote > 0) ? ((fileSize - totalDataWrote) / aveSpeed) : 1;
            etaColumn.set(secondsToTime(secondsToGo));
            averageTransferSpeed.set(speedToString(aveSpeed));
            double perc = ((totalDataWrote > 0) ? (totalDataWrote * 1.0) / (fileSize * 1.0) : 0.0);
            statusS.setValue(numberFormat.format(perc * 100.0) + "%");
            if (diff != 0) {
                tranferRate.setValue(speedToString(diff));
            } else {
                tranferRate.setValue("0");
            }
            if ((totalWroteToFile >= fileSize) || !keepRunning) {
                timer.cancel();
                timer.purge();
                setKeepRunning(false);
                tranferRate.set("");
                statusS.set("Completed");
                averageTransferSpeed.set("");
                etaColumn.set("");
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        randomAccessFile.close();
                        chunksToBeWritten.clear();
                        System.gc();
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();

                System.gc();
            }
        }
    };

    public Download(URL url, long fileSize, @Nullable long[][] chuncksLeft, long totalDataWroteToFile, String destination, String date, String filenameS) {
        this.url = url;
        this.fileSize = fileSize;
        this.totalWroteToFile = totalDataWroteToFile;
        this.totalDataWrote = totalDataWroteToFile;
        this.dateAccessed = new SimpleStringProperty(date);
        this.fileLocation = new SimpleStringProperty(destination);
        this.filename = new SimpleStringProperty(filenameS);
        this.averageTransferSpeed = new SimpleStringProperty("");
        this.statusS = new SimpleStringProperty("Completed!");
        this.tranferRate = new SimpleStringProperty("");
        this.etaColumn = new SimpleStringProperty("");

        if (fileSize > (GIGA)) {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize / (GIGA)) + "GB");
        } else if (fileSize > (MEGA)) {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize / (MEGA)) + "MB");
        } else if (fileSize > KILO) {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize / KILO) + "KB");
        } else {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize) + "B");
        }

        // TODO check url exists
        if (chuncksLeft != null && chuncksLeft.length > 0 && totalDataWroteToFile != fileSize) {
            this.statusS.set("Downloading");
            try {
                randomAccessFile = new RandomAccessFile(destination, "rws");
                randomAccessFile.setLength(fileSize);
            } catch (IOException e) {
                e.printStackTrace();
            }

            Thread wThread = new Thread(writer);
            wThread.setPriority(Thread.MAX_PRIORITY);
            wThread.setDaemon(true);
            wThread.start();

            new Thread(() -> {
                long chunkSize;
                for (long[] a : chuncksLeft) {
                    long start = 0, end = 0;
                    int i = 0;
                    for (long b : a) {
                        if (i == 0) start = b;
                        else end = b;
                        i++;
                    }
                    try {
                        HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
                        String length;

                        length = "bytes=" + start + "-" + end;
                        chunkSize = end - start;

                        httpURLConnection.setRequestProperty("Range", length);
                        httpURLConnection.connect();
                        InputStream inputStream = httpURLConnection.getInputStream();

                        SectionDownload sectionDownload = new SectionDownload(start, chunkSize, inputStream);
                        sectionDownloads.add(sectionDownload);
                        Thread thread = new Thread(sectionDownload);
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MAX_PRIORITY);
                        thread.start();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
            timer = new Timer();
            timer.schedule(timerTask, 0, 1000);
        } else {
            this.statusS = new SimpleStringProperty("Completed");
        }

    }

    public Download(final Map<String, List<String>> headerfields, URL url, File destination, int downloadSplits) {
        this.url = url;
        this.totalDataWrote = 0;
        this.totalWroteToFile = 0;
        this.downloadSplits = downloadSplits;
        this.dateAccessed = new SimpleStringProperty(dateFormat.format(new Date()));
        this.fileLocation = new SimpleStringProperty(destination.toString());
        this.tranferRate = new SimpleStringProperty("0 kB/s");
        this.averageTransferSpeed = new SimpleStringProperty("0 kB/s");
        this.statusS = new SimpleStringProperty("Downloading");
        this.etaColumn = new SimpleStringProperty("");

        HashMap<String, String> valuesMap = new HashMap<>(headerfields.size());
        headerfields.forEach((K, V) -> valuesMap.put(K, V.get(0)));

        String temp = valuesMap.get("Content-Disposition");
        if (temp != null) {
            this.filename = new SimpleStringProperty(URLDecoder.decode(temp.substring(temp.lastIndexOf("UTF-8") + 7, temp.length())));
        } else {
            temp = url.toString();
            temp = temp.substring(temp.lastIndexOf("/") + 1, temp.length());
            this.filename = new SimpleStringProperty(URLDecoder.decode(temp));
        }

        this.fileSize = Long.parseLong(valuesMap.get("Content-Length"));

        if (fileSize > (GIGA)) {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize / (GIGA)) + "GB");
        } else if (fileSize > (MEGA)) {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize / (MEGA)) + "MB");
        } else if (fileSize > KILO) {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize / KILO) + "KB");
        } else {
            this.fileSizeColumn = new SimpleStringProperty(numberFormat.format(fileSize) + "B");
        }

        try {
            randomAccessFile = new RandomAccessFile(destination, "rw");
            randomAccessFile.setLength(fileSize);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Thread wThread = new Thread(writer);
        wThread.setPriority(Thread.MAX_PRIORITY);
        wThread.setDaemon(true);
        wThread.start();

        new Thread(() -> {
            createSections();
        }).start();

        timer = new Timer();
        timer.schedule(timerTask, 0, 1000);
    }


    private void createSections() {
        long chunkSize = (downloadSplits > 1) ? fileSize / downloadSplits : fileSize;
        long chunkPos = 0;
        long targetEnd;
        String length = "";
        for (int i = 0; i < downloadSplits; i++) {
            try {
                HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
                targetEnd = chunkPos + chunkSize;
                if (targetEnd > fileSize) {
                    length = "bytes=" + chunkPos + "-" + fileSize;
                    chunkSize = fileSize - chunkPos;
                } else {
                    length = "bytes=" + chunkPos + "-" + (chunkSize + chunkPos);
                }
                httpURLConnection.setRequestProperty("Range", length);
                httpURLConnection.connect();
                InputStream inputStream = httpURLConnection.getInputStream();

                SectionDownload sectionDownload = new SectionDownload(chunkPos, chunkSize, inputStream);
                sectionDownloads.add(sectionDownload);
                Thread thread = new Thread(sectionDownload, "Part " + i);
                thread.setDaemon(true);
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();
                chunkPos += chunkSize + 1;
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println(length + " vs " + fileSize);
                return;
            }
        }
    }

    private class Chunk {
        private long startPosition;
        private byte[] data;

        Chunk(long startPosition) {
            this.startPosition = startPosition;
        }

        long getStartPosition() {
            return startPosition;
        }

        byte[] getData() {
            return data;
        }

        void setData(byte[] data) {
            this.data = data;
        }

        void deReference() {
            data = null;
        }

    }

    private class SectionDownload implements Runnable {

        private long currentPosition, size;
        private InputStream inputStream;
        private boolean done = false;

        public SectionDownload(long startingPoint, long size, InputStream inputStream) {
            this.currentPosition = startingPoint;
            this.size = size;
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            byte[] shortBuffer = new byte[BUFFER_SIZE];
            byte[] longBuffer = new byte[MAX_BUFFER_SIZE];
            int writePos = 0;
            try {
                int length;
                for (length = inputStream.read(shortBuffer); length != -1 && keepRunning; length = inputStream.read(shortBuffer)) {
                    for (int i = 0; i < length; i++) {
                        longBuffer[writePos++] = shortBuffer[i];
                        totalDataWrote++;
                    }
                    if (writePos > START_TO_WRITE || !keepRunning) { //Start to panic at 9MB, try to write at 7MB
                        if (chunksToBeWritten.hasWaitingConsumer() || !keepRunning || (writePos + BUFFER_SIZE > MAX_BUFFER_SIZE)) {
                            Chunk chunk = new Chunk(currentPosition);
                            currentPosition += writePos;
                            chunk.setData(Arrays.copyOfRange(longBuffer, 0, writePos));
                            writePos = 0;
                            chunksToBeWritten.transfer(chunk);
                        }
                    }
                }
                Chunk chunk = new Chunk(currentPosition);
                chunk.setData(Arrays.copyOfRange(longBuffer, 0, writePos));
                chunksToBeWritten.transfer(chunk);
                inputStream.close();
                if (length == -1) {
                    done = true;
                    statusS.setValue("Completing");
                } else {
                    done = false;
                    statusS.setValue("Paused");
                }

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                setKeepRunning(false);
            } finally {
                shortBuffer = null;
                longBuffer = null;
            }
        }

        boolean isDone() {
            return done;
        }

        long getCurrentPosition() {
            return currentPosition;
        }

        long getSize() {
            return size;
        }
    }

    void setKeepRunning(boolean keepRunning) {
        this.keepRunning = keepRunning;
        if (!keepRunning && !added && !removed) {
            final int[] chuncksLeft = {0};
            sectionDownloads.forEach(sectionDownload -> {
                if (!sectionDownload.isDone()) chuncksLeft[0]++;
            });
            long[][] downloadSections = null;
            if (chuncksLeft[0] != 0) {
                downloadSections = new long[chuncksLeft[0]][2];
                for (int i = 0; !sectionDownloads.isEmpty(); i++) {
                    SectionDownload sectionDownload = sectionDownloads.getFirst();
                    downloadSections[i][0] = sectionDownload.getCurrentPosition();
                    downloadSections[i][1] = sectionDownload.getSize();
                    sectionDownloads.remove(sectionDownload);
                }
            }
            Model.DownloadSave save = new Model.DownloadSave(
                    url, fileSize, downloadSections, totalWroteToFile, fileLocation.get(), dateAccessed.get(),
                    filename.get()
            );
            Model.addToDownloads(save);
            added = true;
        }
    }

    StringProperty getFilename() {
        return filename;
    }

    StringProperty dateAccessedProperty() {
        return dateAccessed;
    }

    StringProperty tranferRateProperty() {
        return tranferRate;
    }

    StringProperty statusSProperty() {
        return statusS;
    }

    StringProperty fileLocationProperty() {
        return fileLocation;
    }

    StringProperty averageTransferSpeedProperty() {
        return averageTransferSpeed;
    }

    private double approxRollingAverage(double avg, double new_sample) {

        avg -= avg / N;
        avg += new_sample / N;

        return avg;
    }

    private static boolean doesURLExist(URL url) throws IOException {
        // We want to check the current URL
        HttpURLConnection.setFollowRedirects(false);

        HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();

        // We don't need to get data
        httpURLConnection.setRequestMethod("HEAD");

        // Some websites don't like programmatic access so pretend to be a browser
        httpURLConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 6.0; en-US; rv:1.9.1.2) Gecko/20090729 Firefox/3.5.2 (.NET CLR 3.5.30729)");
        int responseCode = httpURLConnection.getResponseCode();

        // We only accept response code 200
        return responseCode == HttpURLConnection.HTTP_OK;
    }

    StringProperty fileSizeColumnProperty() {
        return fileSizeColumn;
    }

    private String speedToString(double bytesPerSec) {
        String speed;
        if (bytesPerSec > (GIGA)) {
            speed = numberFormat.format(bytesPerSec / (GIGA)) + "GB/s";
        } else if (bytesPerSec > (MEGA)) {
            speed = numberFormat.format(bytesPerSec / (MEGA)) + "MB/s";
        } else if (bytesPerSec > KILO) {
            speed = numberFormat.format(bytesPerSec / (KILO)) + "KB/s";
        } else {
            speed = numberFormat.format(bytesPerSec / (1.0)) + "B/s";
        }
        return speed;
    }

    boolean isKeepRunning() {
        return keepRunning;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    private String secondsToTime(double seconds) {
        String returnString;
        if (seconds > DAY) {
            returnString = numberFormat.format(seconds / DAY) + " days";
        } else if (seconds > HOUR) {
            returnString = numberFormat.format((seconds / HOUR)) + " hrs";
        } else if (seconds > MIN) {
            returnString = numberFormat.format((seconds / MIN)) + " mins";
        } else {
            returnString = numberFormat.format(seconds) + " s";
        }

        return returnString;
    }

    public StringProperty etaColumnProperty() {
        return etaColumn;
    }
}
