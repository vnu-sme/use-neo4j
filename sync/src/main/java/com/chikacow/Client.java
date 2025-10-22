package com.chikacow;

import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Client {
  public static void main(String[] args) {
    String host = "localhost"; // hoặc IP của server
    int port = 12345;
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    try (Socket socket = new Socket(host, port)) {
      System.out.println("[CLIENT] Đã kết nối tới server " + host + ":" + port);

      BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      PrintWriter output = new PrintWriter(socket.getOutputStream(), true);

      // Thread nhận tin nhắn từ server
      new Thread(() -> {
        String msg;
        try {
          while ((msg = input.readLine()) != null) {
            String time = LocalDateTime.now().format(formatter);
            System.out.println("[LOG " + time + "] Tin nhắn từ Server: " + msg);
          }
        } catch (IOException e) {
          System.out.println("[CLIENT] Mất kết nối với server.");
        }
      }).start();

      // Gửi tin nhắn đến server từ console
      BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
      String message;
      while ((message = console.readLine()) != null) {
        output.println(message);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}

