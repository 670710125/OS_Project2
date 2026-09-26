// FileClient.java
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.*;

public class FileClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8080;
    private static final int WORKER_COUNT = 1; // กำหนด 10 workers ตามข้อกำหนด[cite: 1]

    public static void main(String[] args) {
        String fileName = "test_VDO.mp4"; // ระบุชื่อไฟล์ที่ต้องการดาวน์โหลด
        String mode = "NIO"; // TRADITIONAL หรือเปลี่ยนเป็น "NIO"  เพื่อทดสอบเปรียบเทียบ[cite: 1]
                                                      

        try {
            long startTime = System.currentTimeMillis();

            // 1. ขอขนาดไฟล์จาก Server (INFO <filename>)[cite: 1]
            long fileSize = getFileSize(fileName);
            if (fileSize <= 0) {
                System.out.println("Cannot get file size or file not found.");
                return;
            }
            System.out.println("File Size: " + fileSize + " bytes");

            // 2. เตรียมไฟล์ปลายทาง
            File outputFile = new File("downloaded_" + fileName);
            try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
                raf.setLength(fileSize); // จองขนาดไฟล์ไว้ก่อน[cite: 1]
            }

            // 3. คำนวณช่วงข้อมูล (Chunk Offset & Length) สำหรับ 10 workers[cite: 1]
            long chunkSize = fileSize / WORKER_COUNT;
            ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
            CountDownLatch latch = new CountDownLatch(WORKER_COUNT);

            for (int i = 0; i < WORKER_COUNT; i++) {
                long offset = i * chunkSize;
                long length = (i == WORKER_COUNT - 1) ? (fileSize - offset) : chunkSize; // worker สุดท้ายรับส่วนที่เหลือ[cite: 1]
                int workerId = i;

                executor.execute(() -> {
                    try {
                        downloadChunk(fileName, offset, length, outputFile, mode);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(); // รอให้ทุก worker ทำงานเสร็จ[cite: 1]
            executor.shutdown();

            long endTime = System.currentTimeMillis();
            double durationSec = (endTime - startTime) / 1000.0;
            double throughputMBs = (fileSize / (1024.0 * 1024.0)) / durationSec;

            System.out.println("Download Completed!");
            System.out.printf("Mode: %s | Workers: %d | Time: %.3f s | Throughput: %.2f MB/s\n",
                    mode, WORKER_COUNT, durationSec, throughputMBs);

            // 4. ตรวจสอบ Hash/SHA-256[cite: 1]
            System.out.println("File Hash (SHA-256): " + calculateSHA256(outputFile));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static long getFileSize(String fileName) throws IOException {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            writer.println(Protocol.CMD_INFO + " " + fileName);
            String response = reader.readLine();
            if (response != null && response.startsWith("SIZE")) {
                return Long.parseLong(response.split(" ")[1]);
            }
        }
        return -1;
    }

    private static void downloadChunk(String fileName, long offset, long length, File outputFile, String mode) throws IOException {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             InputStream in = socket.getInputStream()) {

            // ส่ง request: GET <filename> <offset> <length> <mode>
            writer.println(Protocol.CMD_GET + " " + fileName + " " + offset + " " + length + " " + mode);

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String status = reader.readLine(); // รับสถานะ OK
            if (!"OK".equals(status)) return;

            // เขียนลงตำแหน่ง offset ด้วย RandomAccessFile[cite: 1]
            try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
                raf.seek(offset); // ย้ายตัวชี้ไปยัง offset ของตนเอง[cite: 1]
                byte[] buffer = new byte[8192];
                long remaining = length;
                int bytesRead;

                while (remaining > 0 && (bytesRead = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    raf.write(buffer, 0, bytesRead);
                    remaining -= bytesRead;
                }
            }
        }
    }

    private static String calculateSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) hexString.append(String.format("%02x", b));
        return hexString.toString();
    }
}