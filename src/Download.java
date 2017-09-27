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
import java.util.concurrent.TimeUnit;

class Download extends RecursiveTreeObject<Download> {
    private StringProperty filename;
    private StringProperty dateAccessed;
    private StringProperty transferRate;
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
    private final ArrayDeque<Thread> producers = new ArrayDeque<>();
    private Thread consumer;
    private RandomAccessFile randomAccessFile;
    private long totalDataWrote;
    private long totalDataWrotePrev;
    private long totalWroteToFile;
    private Timer timer;
    private boolean keepRunning = true, added = false, done = false, running = false;
    private double N = 0;
    private double aveSpeed = 0;

    private static final DecimalFormat numberFormat = new DecimalFormat("#.00");

    private static final int BUFFER_SIZE = 1024 * 1024; //1MB
    private static final int START_TO_WRITE = BUFFER_SIZE * 8; // Start to write at 7MB
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
            Chunk chunk;
            try {
                while ((chunk = chunksToBeWritten.poll(30, TimeUnit.MINUTES)) != null) {
                        randomAccessFile.seek(chunk.getStartPosition());
                        randomAccessFile.write(chunk.getData());
                        totalWroteToFile += chunk.getData().length;
                        //System.out.println("wrote chunk from: " + chunk.getStartPosition() + " to " + (chunk.getData().length + chunk.getStartPosition()));
                        chunk.deReference();
                        chunk = null;
                }
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
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
                transferRate.setValue(speedToString(diff));
            } else {
                transferRate.setValue("0");
            }
            if ((totalWroteToFile >= fileSize) || !keepRunning) {
                new Thread(() -> {
                    //setKeepRunning(false);
                    if(totalWroteToFile >= fileSize) done = true;
                    boolean threadsStillRunning;
                    do {
                        threadsStillRunning = false;
                        for(Thread t : producers) {
                            if(t.isAlive()) threadsStillRunning = true;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } while (threadsStillRunning);
                    transferRate.set("");
                    if(done) statusS.set("Completed");
                    else statusS.set("Paused");
                    averageTransferSpeed.set("");
                    etaColumn.set("");
                    try {
                        randomAccessFile.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    chunksToBeWritten.clear();
                    running = false;
                    System.gc();
                }).start();
                timer.cancel();
                timer.purge();
            }
        }
    };

    public Download(URL url, long fileSize, @Nullable ArrayDeque<String> chuncksLeft,
                    long totalDataWroteToFile, String destination, String date, String filenameS, boolean done) {
        this.url = url;
        this.fileSize = fileSize;
        //System.out.println("File Size: " + fileSize);
        this.totalWroteToFile = totalDataWroteToFile;
        this.totalDataWrote = totalDataWroteToFile;
        this.dateAccessed = new SimpleStringProperty(date);
        this.fileLocation = new SimpleStringProperty(destination);
        this.filename = new SimpleStringProperty(filenameS);
        this.averageTransferSpeed = new SimpleStringProperty("");
        this.statusS = new SimpleStringProperty("Completed!");
        this.transferRate = new SimpleStringProperty("");
        this.etaColumn = new SimpleStringProperty("");
        this.done = done;

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
        if (chuncksLeft != null && totalDataWroteToFile != fileSize && !done) {
            this.statusS.set("Downloading");
            try {
                randomAccessFile = new RandomAccessFile(destination, "rws");
                randomAccessFile.setLength(fileSize);
            } catch (IOException e) {
                e.printStackTrace();
            }

            consumer = new Thread(writer);
            consumer.setPriority(Thread.MAX_PRIORITY);
            consumer.setDaemon(true);
            consumer.start();

            new Thread(() -> {
                long chunkSize;
                for (String length : chuncksLeft) {
                    try {
                        HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();

                        long start = Long.parseLong(length.substring(length.indexOf("=") + 1, length.lastIndexOf("-")));
                        long end = Long.parseLong(length.substring(length.lastIndexOf("-") + 1));
                        chunkSize = end - start;

                        //System.out.println("Range: " + length + " vs " + start + " , " + end);
                        httpURLConnection.setRequestProperty("Range", length);
                        httpURLConnection.connect();
                        InputStream inputStream = httpURLConnection.getInputStream();

                        SectionDownload sectionDownload = new SectionDownload(start, chunkSize, inputStream);
                        sectionDownloads.add(sectionDownload);
                        Thread thread = new Thread(sectionDownload, String.valueOf(start));
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MAX_PRIORITY);
                        thread.start();
                        producers.add(thread);
                    } catch (IOException e) {
                        //e.printStackTrace();
                        //System.out.println(length);
                    }
                }
            }).start();
            timer = new Timer();
            timer.schedule(timerTask, 0, 1000);
            running = true;
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
        this.transferRate = new SimpleStringProperty("0 kB/s");
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
        //System.out.println("Length: " + this.fileSize);

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
            if (!destination.exists()) randomAccessFile.setLength(fileSize);
        } catch (IOException e) {
            e.printStackTrace();
        }

        consumer = new Thread(writer);
        consumer.setPriority(Thread.MAX_PRIORITY);
        consumer.setDaemon(true);
        consumer.start();

        new Thread(this::createSections).start();

        timer = new Timer();
        timer.schedule(timerTask, 0, 1000);
        running = true;
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
                producers.add(thread);
                chunkPos += chunkSize + 1;
            } catch (IOException e) {
                //e.printStackTrace();
                //System.out.println(length + " vs " + fileSize);
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

        private long currentPosition, size, endPoint;
        private InputStream inputStream;
        private boolean done = false;

        public SectionDownload(long startingPoint, long size, InputStream inputStream) {
            this.currentPosition = startingPoint;
            this.endPoint = startingPoint + size;
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
                    if (writePos > START_TO_WRITE) {
                        Chunk chunk = new Chunk(currentPosition);
                        currentPosition += writePos;
                        chunk.setData(Arrays.copyOfRange(longBuffer, 0, writePos));
                        writePos = 0;
                        chunksToBeWritten.transfer(chunk);
                    }
                }
                //System.out.println("closing chunk");
                Chunk chunk = new Chunk(currentPosition);
                chunk.setData(Arrays.copyOfRange(longBuffer, 0, writePos));
                chunksToBeWritten.transfer(chunk);
                inputStream.close();
                if (length == -1) {
                    done = true;
                } else {
                    done = false;
                }

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                setKeepRunning(false);
            } finally {
                shortBuffer = null;
                longBuffer = null;
            }
            //System.out.println("Thread done");
        }

        boolean isDone() {
            return done;
        }

        long getCurrentPosition() {
            return currentPosition;
        }

        public long getEndPoint() {
            return endPoint;
        }
    }

    void setKeepRunning(boolean keepRunning) {
        this.keepRunning = keepRunning;
        //System.out.println("Running :" + this.keepRunning);
        //System.out.println("zero");
        if (!keepRunning && !added && !removed) {
            int no = 0;
            for (SectionDownload sectionDownload : sectionDownloads) {
                if (!sectionDownload.isDone()) no++;
                //System.out.println("loop 0");
            }
            ArrayDeque<String> chunksLeft = null;
            if (no > 0) {

                boolean threadsDone = true;
                do {
                    for (Thread t : producers) {
                        if (t.isAlive()) {
                            threadsDone = false;
                            //System.out.println(t.getName());
                        }
                    }
                    if (!threadsDone) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            //e.printStackTrace();
                        }
                    }
                } while (!threadsDone);

                chunksLeft = new ArrayDeque<>(no);
                for(SectionDownload sectionDownload : sectionDownloads) {
                    chunksLeft.add("bytes=" + sectionDownload.getCurrentPosition() + "-" + (sectionDownload.getEndPoint()));
                }
            }
            Model.DownloadSave save = new Model.DownloadSave(
                    url, fileSize, chunksLeft, totalWroteToFile, fileLocation.get(), dateAccessed.get(),
                    filename.get(), done
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

    StringProperty transferRateProperty() {
        return transferRate;
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

    public boolean isDone() {
        return done;
    }

    public boolean isRunning() {
        return running;
    }
}
