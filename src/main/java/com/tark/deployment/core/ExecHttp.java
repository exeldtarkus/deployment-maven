package com.tark.deployment.core;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

public class ExecHttp {
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String WHITE = "\033[97m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String GRAY = "\033[90m";
    private static final String LIGHT_YELLOW = "\033[93m";

    /**
     * Simple GET request
     */
    public static int GET(String url) {
        return execute(url, "GET", null, null, null, null);
    }

    /**
     * GET request dengan Basic Authentication (Dipakai untuk cek Nexus di Deploy.java)
     */
    public static int GET(String url, String username, String password) {
        return execute(url, "GET", null, null, username, password);
    }

    /**
     * Simple POST request (Default Content-Type: application/json)
     */
    public static int POST(String url, String payload) {
        return execute(url, "POST", payload, "application/json", null, null);
    }

    /**
     * POST request dengan custom Content-Type
     */
    public static int POST(String url, String payload, String contentType) {
        return execute(url, "POST", payload, contentType, null, null);
    }

    /**
     * POST request dengan Basic Authentication
     */
    public static int POST(String url, String payload, String username, String password) {
        return execute(url, "POST", payload, "application/json", username, password);
    }

    /**
     * Simple PUT request (Default Content-Type: application/json)
     */
    public static int PUT(String url, String payload) {
        return execute(url, "PUT", payload, "application/json", null, null);
    }

    /**
     * Simple DELETE request
     */
    public static int DELETE(String url) {
        return execute(url, "DELETE", null, null, null, null);
    }


    // =========================================================================
    // PRIVATE CORE ENGINE
    // =========================================================================

    private static int execute(String targetUrl, String method, String payload, String contentType, String username, String password) {
        try {
            URL url = new URL(targetUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            connection.setRequestMethod(method.toUpperCase());
            connection.setConnectTimeout(5000); // 5 detik
            connection.setReadTimeout(5000);    // 5 detik

            // Basic Auth Header
            if (username != null && !username.isEmpty() && password != null) {
                String auth = username + ":" + password;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));
                connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
            }

            // Payload / Body Writer
            if (payload != null && !payload.isEmpty()) {
                connection.setDoOutput(true);
                if (contentType != null && !contentType.isEmpty()) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = payload.getBytes("UTF-8");
                    os.write(input, 0, input.length);
                }
            }

            int responseCode = connection.getResponseCode();
            connection.disconnect();
            
            return responseCode;
            
        } catch (Exception e) {
            System.err.println("[DEBUG] Koneksi HTTP Gagal ke " + targetUrl + " | Pesan: " + e.getMessage());
            return -1;
        }
    }
}