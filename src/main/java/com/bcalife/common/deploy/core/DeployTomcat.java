package com.bcalife.common.deploy.core;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.Base64;

public class DeployTomcat {

    static class Server {
        String name;
        String url;
        String username;
        String password;
    }

    public static void main(String[] args) {
        try {
            String deployDir = "deployment";
            Scanner sc = new Scanner(System.in);
            String projectId = (args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0]
                : "APP";

            UI.clearScreen();
            UI.printHeader(projectId + " TOMCAT DEPLOYMENT TOOL");

            // [STEP 1]
            UI.printStep("1/4", "Application Configuration");
            System.out.print(UI.GRAY + " > Enter APP_IDENTITY (Default: " + projectId + "): " + UI.RESET);
            String userInput = sc.nextLine().trim();
            String appId = userInput.isEmpty() ? projectId : userInput;
            UI.printInfo("Target App ID: " + UI.CYAN + appId + UI.RESET);

            Path secretPath = Paths.get(deployDir, "secret-tomcat.json");
            List<Server> servers = new ArrayList<>();
            Server selectedServer = null;

            if (Files.exists(secretPath)) {
                servers = loadServers(secretPath);
                if (!servers.isEmpty()) {
                    UI.printServer("Available Tomcat Servers:");
                    for (int i = 0; i < servers.size(); i++) {
                        System.out.println(UI.CYAN + " [" + (i + 1) + "] " + UI.RESET + servers.get(i).name + UI.GRAY + " (" + servers.get(i).url + ")" + UI.RESET);
                    }
                    System.out.println(UI.CYAN + " [" + (servers.size() + 1) + "] " + UI.RESET + "Add New Server");

                    System.out.print(UI.GRAY + "\n > Select option: " + UI.RESET);
                    try {
                        int choice = Integer.parseInt(sc.nextLine().trim());
                        if (choice >= 1 && choice <= servers.size()) {
                            selectedServer = servers.get(choice - 1);
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (selectedServer == null) {
                UI.printWarning("Setup New Tomcat Server");
                selectedServer = new Server();
                System.out.print(UI.GRAY + " > Enter Tomcat Manager URL: " + UI.RESET);
                selectedServer.url = sc.nextLine().trim().replaceAll("/+$", "");
                selectedServer.name = extractNameFromUrl(selectedServer.url);
                System.out.print(UI.GRAY + " > Username: " + UI.RESET);
                selectedServer.username = sc.nextLine().trim();
                System.out.print(UI.GRAY + " > Password: " + UI.RESET);
                selectedServer.password = sc.nextLine().trim();

                System.out.print(UI.GRAY + " > Save server? (y/n): " + UI.RESET);
                if (sc.nextLine().equalsIgnoreCase("y")) {
                    servers.add(selectedServer);
                    saveServers(secretPath, servers);
                    UI.printSuccess("Server credentials saved.");
                }
            }
            UI.printInfo("Target Server: " + UI.CYAN + selectedServer.name + UI.RESET);

            // [STEP 2]
            UI.printStep("2/4", "Maven Profile Selection");
            List<String> profiles = extractProfiles(Paths.get("pom.xml"));
            String selectedProfile = null;
            if (!profiles.isEmpty()) {
                UI.printProfile("Available Profiles:");
                for (int i = 0; i < profiles.size(); i++) {
                    System.out.println(UI.CYAN + " [" + (i + 1) + "] " + UI.RESET + profiles.get(i));
                }
                System.out.print(UI.GRAY + "\n > Select profile number: " + UI.RESET);
                String input = sc.nextLine().trim();
                if (!input.isEmpty()) {
                    try {
                        selectedProfile = profiles.get(Integer.parseInt(input) - 1);
                    } catch (Exception ignored) {}
                }
            }
            UI.printInfo("Active Profile: " + UI.CYAN + (selectedProfile != null ? selectedProfile : "Default") + UI.RESET);

            // [STEP 3]
            UI.printStep("3/4", "Building Maven Artifact");
            String cmd = (selectedProfile != null) ? "mvn clean install -DskipTests -P" + selectedProfile : "mvn clean install -DskipTests";
            if (!runCommand(cmd)) {
                UI.printError("Maven Build Failed!");
                System.exit(1);
            }
            File war = findWar();
            UI.printSuccess("Artifact ready: " + war.getName());

            // [STEP 4]
            UI.printStep("4/4", "Deploying to Tomcat");
            String deployUrl = selectedServer.url + "/manager/text/deploy?path=/" + appId + "&update=true";
            String response = deployWar(deployUrl, war, selectedServer.username, selectedServer.password);

            if (response.startsWith("OK")) {
                UI.printSuccess("DEPLOYMENT FINISHED SUCCESSFULLY!");
                System.out.println(UI.CYAN + "🔗 URL: " + selectedServer.url + "/" + appId + UI.RESET);
            } else {
                UI.printError("Tomcat rejected deployment.");
                System.out.println(UI.YELLOW + response + UI.RESET);
            }

        } catch (Exception e) {
            UI.printError("System error: " + e.getMessage());
        }
    }

    // --- REUSE OLD LOGIC (loadServers, saveServers, runCommand, deployWar, dll) ---
    // Pastikan di runCommand ditambahkan penanganan JAVA_HOME seperti sebelumnya
    static boolean runCommand(String cmd) {
        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWin) cmd = cmd.replace("\\", "/");

            ProcessBuilder pb = new ProcessBuilder(isWin ? "cmd.exe" : "bash", isWin ? "/c" : "-c", cmd);
            pb.inheritIO();
            
            String jh = System.getenv("JAVA_HOME");
            if (jh != null) pb.environment().put("JAVA_HOME", isWin ? jh.replace("\\", "/") : jh);
            
            return pb.start().waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    static List<Server> loadServers(Path path) throws Exception {
        List<Server> list = new ArrayList<>();
        String content = new String(Files.readAllBytes(path));
        Pattern p = Pattern.compile("\"name\"\\s*:\\s*\"(.*?)\".*?\"url\"\\s*:\\s*\"(.*?)\".*?\"username\"\\s*:\\s*\"(.*?)\".*?\"password\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher m = p.matcher(content);
        while (m.find()) {
            Server s = new Server();
            s.name = m.group(1); s.url = m.group(2); s.username = m.group(3); s.password = m.group(4);
            list.add(s);
        }
        return list;
    }

    static void saveServers(Path path, List<Server> servers) throws Exception {
        StringBuilder sb = new StringBuilder("{\n  \"servers\": [\n");
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            sb.append("    {\"name\":\"").append(s.name).append("\",\"url\":\"").append(s.url)
              .append("\",\"username\":\"").append(s.username).append("\",\"password\":\"").append(s.password).append("\"}");
            if (i < servers.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");
        Files.write(path, sb.toString().getBytes());
    }

    static String extractNameFromUrl(String url) { return url.replaceAll("https?://", "").replaceAll(":[0-9]+", ""); }
    
    static List<String> extractProfiles(Path pom) throws Exception {
        List<String> list = new ArrayList<>();
        if (!Files.exists(pom)) return list;
        Matcher m = Pattern.compile("<profile>\\s*<id>(.*?)</id>", Pattern.DOTALL).matcher(new String(Files.readAllBytes(pom)));
        while (m.find()) list.add(m.group(1).trim());
        return list;
    }

    static File findWar() {
        File[] f = new File("target").listFiles((d, n) -> n.endsWith(".war"));
        return (f != null && f.length > 0) ? f[0] : null;
    }

    static String deployWar(String urlStr, File war, String user, String pass) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setDoOutput(true); conn.setRequestMethod("PUT");
        String auth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        conn.setRequestProperty("Authorization", "Basic " + auth);
        try (OutputStream os = conn.getOutputStream(); FileInputStream fis = new FileInputStream(war)) {
            byte[] buf = new byte[8192]; int len;
            while ((len = fis.read(buf)) != -1) os.write(buf, 0, len);
        }
        InputStream is = (conn.getResponseCode() < 300) ? conn.getInputStream() : conn.getErrorStream();
        return new Scanner(is).useDelimiter("\\A").next();
    }
}