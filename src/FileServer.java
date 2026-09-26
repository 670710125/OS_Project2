// FileServer.java
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.nio.channels.WritableByteChannel;

public class FileServer {
    private static final int PORT = 8080;
    private static final String SERVER_DIR = "./server_files"; // โฟลเดอร์เก็บไฟล์บน Server

    public static void main(String[] args) {
        File dir = new File(SERVER_DIR);
        if (!dir.exists()) dir.mkdirs();

        ExecutorService threadPool = Executors.newCachedThreadPool(); // หรือใช้ Virtual Threads ใน Java 21+

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null) return;

            String[] tokens = requestLine.split(" ");
            String command = tokens[0].toUpperCase();

            switch (command) {
                case Protocol.CMD_LIST:
                    File dir = new File(SERVER_DIR);
                    File[] files = dir.listFiles();
                    StringBuilder listResp = new StringBuilder();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile()) listResp.append(f.getName()).append(" ").append(f.length()).append("\n");
                        }
                    }
                    out.write((listResp.toString() + "\n").getBytes());
                    break;

                case Protocol.CMD_INFO:
                    String fileName = tokens[1];
                    File file = new File(SERVER_DIR, fileName);
                    if (file.exists() && file.isFile()) {
                        out.write(("SIZE " + file.length() + "\n").getBytes());
                    } else {
                        out.write(("ERROR 404 File_Not_Found\n").getBytes());
                    }
                    break;

                case Protocol.CMD_GET:
                    // รูปแบบคำสั่ง: GET <filename> <offset> <length> <mode: TRADITIONAL|NIO>
                    String getFile = tokens[1];
                    long offset = Long.parseLong(tokens[2]);
                    long length = Long.parseLong(tokens[3]);
                    String mode = tokens.length > 4 ? tokens[4] : "TRADITIONAL";

                    File targetFile = new File(SERVER_DIR, getFile);
                    if (!targetFile.exists()) {
                        out.write(("ERROR 404 File_Not_Found\n").getBytes());
                        return;
                    }

                    out.write(("OK\n").getBytes());
                    out.flush();

                    if ("NIO".equalsIgnoreCase(mode)) {
                        // โหมด NIO: ใช้ FileChannel zero-copy / native transfer
                        try (FileChannel fileChannel = FileChannel.open(Paths.get(targetFile.getAbsolutePath()), StandardOpenOption.READ);
                             WritableByteChannel targetChannel = java.nio.channels.Channels.newChannel(out)) {
                            fileChannel.transferTo(offset, length, targetChannel); // Native transfer
                        }
                    } else {
                        // โหมด Traditional I/O: อ่านใส่ Buffer
                        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "r")) {
                            raf.seek(offset);
                            byte[] buffer = new byte[8192];
                            long bytesToRead = length;
                            int bytesRead;
                            while (bytesToRead > 0 && (bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                                out.write(buffer, 0, bytesRead);
                                bytesToRead -= bytesRead;
                            }
                            out.flush();
                        }
                    }
                    break;

                default:
                    out.write(("ERROR 400 Bad_Request\n").getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}