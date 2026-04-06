package com.bcalife.common.deploy.core;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.regex.*;


public class Deploy {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String WHITE = "\033[97m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String GRAY = "\033[90m";
    private static final String LIGHT_YELLOW = "\033[93m";

    public static void main(String[] args) throws Exception {
        String pId = (args.length > 0 && args[0] != null && !args[0].isEmpty()) ? args[0] : "APP";
        run(pId, new ArrayList<>());
    }

    public static void run(String projectId, List<String> requiredPackages) throws Exception {
        Scanner scanner = new Scanner(System.in);

        // Initialization
        String scriptDir = System.getProperty("user.dir");
        String rootDir = scriptDir; // Asumsi dijalankan dari root folder
        String deployDir = "deployment";
        Path pomPath = Paths.get(rootDir, "pom.xml");

        // Clear screen (ANSI escape code)
        System.out.print("\033[H\033[2J");
        System.out.flush();

        System.out.println(CYAN + "====================================================" + RESET);
        System.out.println(CYAN + "         " + projectId.toUpperCase() + " DEPLOYMENT AUTOMATION TOOL            " + RESET);
        System.out.println(CYAN + "====================================================" + RESET);

        // --- STEP 1: Metadata Extraction ---
        System.out.println(YELLOW + "[1/7] Extracting metadata from pom.xml..." + RESET);
        String appVersion = "unknown";

        if (Files.exists(pomPath)) {
            String pomContent = new String(Files.readAllBytes(pomPath));
            Matcher m = Pattern.compile("<version>(.*?)</version>").matcher(pomContent);
            if (m.find()) {
                appVersion = m.group(1).trim();
            }
        } else {
            System.out.println(YELLOW + "[!] Warning: pom.xml not found. Falling back to 'unknown'." + RESET);
        }

        if (appVersion.isEmpty()) appVersion = "unknown";
        System.out.println(GREEN + "[OK] Application Version detected: " + appVersion + RESET);

        // --- STEP 2: User Input ---
        System.out.println("\n" + GRAY + "----------------------------------------------------" + RESET);
        System.out.println(" " + LIGHT_YELLOW + "[?] DEPLOYMENT CONFIGURATION" + RESET);
        System.out.println(GRAY + "----------------------------------------------------" + RESET);

        System.out.print(" > Enter APP_IDENTITY (e.g., " + projectId + ") [Default: " + projectId + "]: ");
        String userAppId = scanner.nextLine().trim();
        String appId = userAppId.isEmpty() ? projectId : userAppId;

        String dockerTag = "";
        while (true) {
            System.out.print(" > Enter DOCKER_TAG (Number 1-1000) [Default: latest]: ");
            String userDockerTag = scanner.nextLine().trim();

            if (userDockerTag.isEmpty()) {
                dockerTag = "latest";
                break;
            } else if (userDockerTag.matches("^[0-9]+$")) {
                int tagNum = Integer.parseInt(userDockerTag);
                if (tagNum >= 1 && tagNum <= 1000) {
                    dockerTag = "v" + userDockerTag;
                    break;
                }
            }
            System.out.println(" > " + RED + "[!] Invalid input. Please enter a number between 1 and 1000, or leave blank for 'latest'." + RESET);
        }

        String replicaCountStr = "";
        while (true) {
            System.out.print(" > Enter Replica Count (Max 3) [Default: 1]: ");
            String userReplica = scanner.nextLine().trim();

            if (userReplica.isEmpty()) {
                replicaCountStr = "1";
                break;
            } else if (userReplica.matches("^[0-9]+$")) {
                int repNum = Integer.parseInt(userReplica);
                if (repNum >= 1 && repNum <= 3) {
                    replicaCountStr = userReplica;
                    break;
                }
            }
            System.out.println(" > " + RED + "[!] Invalid input. Please enter a number between 1 and 3, or leave blank for default (1)." + RESET);
        }

        String combinedTag = (appId + "_" + appVersion + "_" + dockerTag).toLowerCase();
        System.out.println("\n" + CYAN + "[*] Target Image Tag: " + combinedTag + RESET);
        System.out.println(CYAN + "[*] Target Replicas : " + replicaCountStr + RESET);

        // --- STEP 3: Environment Validation ---
        System.out.println("\n" + YELLOW + "[2/7] Validating local environment..." + RESET);
        Path deployM2Dir = Paths.get(rootDir, deployDir, ".m2");
        Path deployLibsDir = Paths.get(rootDir, deployDir, "libs");

        Files.createDirectories(deployM2Dir);
        Files.createDirectories(deployLibsDir);

        Path deployM2File = deployM2Dir.resolve("settings.xml");
        
        if (!Files.exists(deployM2File)) {
            String defaultHome = System.getProperty("user.home");
            Path defaultM2Path = Paths.get(defaultHome, ".m2", "settings.xml");
            
            System.out.print("[?] settings.xml not found in project. Provide source path [Default: " + defaultM2Path.toString() + "]: ");
            String m2Path = scanner.nextLine().trim();
            
            Path m2PathReal;
            if (m2Path.isEmpty()) {
                m2PathReal = defaultM2Path;
            } else {
                m2Path = m2Path.replaceFirst("^~", defaultHome.replace("\\", "\\\\"));
                m2PathReal = Paths.get(m2Path);
            }

            if (Files.exists(m2PathReal)) {
                System.out.println(GRAY + "[*] Copying settings.xml to deployment context from " + m2PathReal.toString() + "..." + RESET);
                Files.copy(m2PathReal, deployM2File, StandardCopyOption.REPLACE_EXISTING);
            } else {
                System.out.println(RED + "[X] CRITICAL ERROR: Source settings.xml not found at " + m2PathReal.toString() + RESET);
                System.exit(1);
            }
        }

        // --- STEP INITIAL CHECK ---
        System.out.println("\n" + YELLOW + "[CHECK] Executing pre-flight checks..." + RESET);

        // [CHECK] - 1. Check Kubernetes Contexts
        System.out.println(GRAY + " > Checking Kubernetes contexts..." + RESET);
        String kubeContextsOut = execCommand("kubectl config get-contexts -o name", false).trim();
        List<String> contexts = new ArrayList<>();
        
        if (!kubeContextsOut.isEmpty() && !kubeContextsOut.toLowerCase().contains("error")) {
            for (String line : kubeContextsOut.split("\n")) {
                if (!line.trim().isEmpty()) {
                    contexts.add(line.trim());
                }
            }
        }

        Path kubeConfigDeployPath = Paths.get(rootDir, deployDir, "kube-config.yml");
        Path kubeConfigDefaultPath = Paths.get(System.getProperty("user.home"), ".kube", "config");

        if (contexts.isEmpty()) {
            if (!Files.exists(kubeConfigDeployPath)) {
                // Create dummy file and exit
                String dummyKubeConfig = "apiVersion: v1\n" +
                        "clusters:\n" +
                        "- cluster:\n" +
                        "    certificate-authority-data: xx==\n" +
                        "    server: https://1.1.1.1:8080\n" +
                        "  name: microk8s-cluster-213\n" +
                        "contexts:\n" +
                        "- context:\n" +
                        "    cluster: microk8s-cluster-213\n" +
                        "    user: admin\n" +
                        "  name: microk8s-cluster-213\n" +
                        "users:\n" +
                        "- name: admin\n" +
                        "  user:\n" +
                        "    client-certificate-data: xx\n" +
                        "    client-key-data: xxxx\n" +
                        "kind: Config\n" +
                        "preferences: {}";
                Files.write(kubeConfigDeployPath, dummyKubeConfig.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println(YELLOW + "[!] No Kubernetes contexts found, and " + kubeConfigDeployPath.getFileName() + " does not exist." + RESET);
                System.out.println(YELLOW + "[!] A template has been generated at: " + kubeConfigDeployPath.toAbsolutePath() + RESET);
                System.out.println(LIGHT_YELLOW + "[!] ACTION REQUIRED: Please edit this file with your cluster details, then run this deployment tool again." + RESET);
                System.exit(1);
            } else {
                // Copy existing deployment/kube-config.yml to default .kube/config
                System.out.println(GRAY + " > Copying " + kubeConfigDeployPath.getFileName() + " to default kube location (" + kubeConfigDefaultPath.toString() + ")..." + RESET);
                Files.createDirectories(kubeConfigDefaultPath.getParent());
                Files.copy(kubeConfigDeployPath, kubeConfigDefaultPath, StandardCopyOption.REPLACE_EXISTING);
                
                // Re-fetch contexts after copying
                kubeContextsOut = execCommand("kubectl config get-contexts -o name", false).trim();
                if (!kubeContextsOut.isEmpty() && !kubeContextsOut.toLowerCase().contains("error")) {
                    for (String line : kubeContextsOut.split("\n")) {
                        if (!line.trim().isEmpty()) {
                            contexts.add(line.trim());
                        }
                    }
                }
                
                if (contexts.isEmpty()) {
                    System.out.println(RED + "[X] CRITICAL ERROR: Copied kube config but still no valid contexts found. Please check the file format." + RESET);
                    System.exit(1);
                }
            }
        }

        // Display context choices to user
        System.out.println(CYAN + "Available Kubernetes Contexts:" + RESET);
        for (int i = 0; i < contexts.size(); i++) {
            System.out.println("  " + (i + 1) + ") " + contexts.get(i));
        }

        String selectedContext = "";
        while (true) {
            System.out.print(" > Select Context (1-" + contexts.size() + "): ");
            String ctxInput = scanner.nextLine().trim();
            try {
                int ctxChoice = Integer.parseInt(ctxInput);
                if (ctxChoice >= 1 && ctxChoice <= contexts.size()) {
                    selectedContext = contexts.get(ctxChoice - 1);
                    break;
                } else {
                    System.out.println(" > " + RED + "[!] Number out of range. Please choose between 1 and " + contexts.size() + RESET);
                }
            } catch (NumberFormatException e) {
                System.out.println(" > " + RED + "[!] Invalid input. Please enter a valid integer number." + RESET);
            }
        }

        System.out.println(GRAY + " > Switching to context: " + selectedContext + "..." + RESET);
        execCommand("kubectl config use-context " + selectedContext, false);
        System.out.println(GREEN + "[OK] Kubernetes context set to: " + selectedContext + RESET);

        // [CHECK] - 2. Check if Docker is active (Compatible with PowerShell and Bash)
        System.out.println(GRAY + " > Checking if Docker is active..." + RESET);
        try {
            String dockerCmd = IS_WINDOWS ? 
                "docker info 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }" : 
                "docker info >/dev/null 2>&1";

            ProcessBuilder pb = IS_WINDOWS ?
                new ProcessBuilder("powershell.exe", "-Command", dockerCmd) :
                new ProcessBuilder("bash", "-c", dockerCmd);
            
            Process p = pb.start();
            if (p.waitFor() != 0) {
                System.out.println(RED + "[X] CRITICAL ERROR: Docker is not active or not running. Please start Docker." + RESET);
                System.exit(1);
            }
            System.out.println(GREEN + "[OK] Docker daemon is active." + RESET);
        } catch (Exception e) {
            System.out.println(RED + "[X] CRITICAL ERROR: Failed to execute 'docker info'. Is Docker installed and in PATH?" + RESET);
            System.exit(1);
        }

        // [CHECK] - 3 & 4. Check settings.xml for Nexus config and test HTTP 200 connection
        System.out.println(GRAY + " > Checking Nexus mirror configuration and connection..." + RESET);
        String settingsContentInit = new String(Files.readAllBytes(deployM2File));
        
        if (!settingsContentInit.contains("<id>nexus</id>")) {
            System.out.println(RED + "[X] CRITICAL ERROR: settings.xml is missing <id>nexus</id> tag." + RESET);
            System.exit(1);
        }

        // Ekstrak URL dari tag <mirror>
        Matcher mirrorBlockMatcher = Pattern.compile("<mirror>([\\s\\S]*?)</mirror>").matcher(settingsContentInit);
        String nexusUrlTest = "";
        while (mirrorBlockMatcher.find()) {
            String mirrorBlock = mirrorBlockMatcher.group(1);
            if (mirrorBlock.contains("<id>nexus</id>")) {
                Matcher urlM = Pattern.compile("<url>(.*?)</url>").matcher(mirrorBlock);
                if (urlM.find()) nexusUrlTest = urlM.group(1).trim();
                break;
            }
        }

        String nexusHost = "";
        
        if (!nexusUrlTest.isEmpty()) {
            try {
                URL urlObj = new URL(nexusUrlTest);
                nexusHost = urlObj.getHost(); // Ekstrak host untuk test koneksi TCP di bawah
                
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000); // Timeout 5 detik
                
                // Ekstrak username & password dari tag <server>
                Matcher serverBlockMatcher = Pattern.compile("<server>([\\s\\S]*?)</server>").matcher(settingsContentInit);
                String username = "";
                String password = "";

                while (serverBlockMatcher.find()) {
                    String serverBlock = serverBlockMatcher.group(1);
                    if (serverBlock.contains("<id>nexus</id>")) {
                        Matcher uMatch = Pattern.compile("<username>(.*?)</username>").matcher(serverBlock);
                        if (uMatch.find()) username = uMatch.group(1).trim();
                        
                        Matcher pMatch = Pattern.compile("<password>(.*?)</password>").matcher(serverBlock);
                        if (pMatch.find()) password = pMatch.group(1).trim();
                        break;
                    }
                }
                
                if (!username.isEmpty() && !password.isEmpty()) {
                    String auth = username + ":" + password;
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
                    conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                    System.out.println(GRAY + " > Validating using credentials for user: " + username + "..." + RESET);
                } else {
                    System.out.println(YELLOW + " > [!] No credentials found in <server> block for 'nexus'. Attempting anonymous request." + RESET);
                }
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == 401) {
                    System.out.println(RED + "[X] CRITICAL ERROR: Received HTTP 401 (Unauthorized). Username/Password is incorrect or missing in settings.xml!" + RESET);
                    System.exit(1);
                } else if (responseCode != 200) {
                    System.out.println(RED + "[X] CRITICAL ERROR: Connection to Nexus failed! Expected HTTP 200 but got HTTP " + responseCode + RESET);
                    System.exit(1);
                }
                
                System.out.println(GREEN + "[OK] Successfully connected and authenticated to Nexus Maven HTTP." + RESET);
                
            } catch (Exception e) {
                System.out.println(RED + "[X] CRITICAL ERROR: Could not connect to Nexus at " + nexusUrlTest + ". Exception: " + e.getMessage() + RESET);
                System.exit(1);
            }
        } else {
            System.out.println(RED + "[X] CRITICAL ERROR: Could not parse Nexus <url> from settings.xml inside <mirror> block." + RESET);
            System.exit(1);
        }

        // [CHECK] - 5. Check deployment/secret-k8s.json for port-nexus-docker
        System.out.println(GRAY + " > Checking secret-k8s.json for port-nexus-docker..." + RESET);
        Path secretK8sPath = Paths.get(rootDir, deployDir, "secret-k8s.json");
        int portNexusDocker = 31208; // Default value
        boolean needToAskPort = false;

        if (Files.exists(secretK8sPath)) {
            String secretContent = new String(Files.readAllBytes(secretK8sPath));
            Matcher portMatcher = Pattern.compile("\"port-nexus-docker\"\\s*:\\s*(\\d+)").matcher(secretContent);
            if (portMatcher.find()) {
                portNexusDocker = Integer.parseInt(portMatcher.group(1));
                System.out.println(GREEN + "[OK] Found port-nexus-docker: " + portNexusDocker + " in secret-k8s.json" + RESET);
            } else {
                needToAskPort = true;
            }
        } else {
            needToAskPort = true;
        }

        if (needToAskPort) {
            while (true) {
                System.out.print(" > Enter port-nexus-docker [Default: 31208]: ");
                String userPort = scanner.nextLine().trim();
                if (userPort.isEmpty()) {
                    portNexusDocker = 31208;
                    break;
                } else if (userPort.matches("^\\d+$")) {
                    portNexusDocker = Integer.parseInt(userPort);
                    break;
                } else {
                    System.out.println(" > " + RED + "[!] Invalid input. Please enter a valid integer number." + RESET);
                }
            }
            
            // Simpan atau buat file secret-k8s.json
            String jsonContent = "{\n  \"port-nexus-docker\": " + portNexusDocker + "\n}";
            Files.write(secretK8sPath, jsonContent.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println(GREEN + "[OK] Saved port-nexus-docker (" + portNexusDocker + ") to " + secretK8sPath.getFileName() + RESET);
        }

        // [CHECK] - 6. Test TCP Connection to ${hostNexus}:${port-nexus-docker}
        System.out.println(GRAY + " > Testing TCP connection to Nexus Docker Registry (" + nexusHost + ":" + portNexusDocker + ")..." + RESET);
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(nexusHost, portNexusDocker), 5000); // 5 detik timeout
            System.out.println(GREEN + "[OK] Connection to Nexus Docker Registry successful." + RESET);
        } catch (Exception e) {
            System.out.println(RED + "[X] CRITICAL ERROR: Could not connect to Nexus Docker Registry at " + nexusHost + ":" + portNexusDocker + ". Exception: " + e.getMessage() + RESET);
            System.exit(1);
        }

        // [CHECK] - 7. Docker Insecure Registry Configuration (Detect, Inject, Apply)
        String targetRegistry = nexusHost + ":" + portNexusDocker;
        System.out.println(GRAY + " > Checking Docker insecure-registries configuration for " + targetRegistry + "..." + RESET);

        // --- 1. MENDETEKSI ---
        String dockerInfoCmd2 = IS_WINDOWS ?
            "if (docker info 2>$null | Select-String '" + targetRegistry + "') { echo 'FOUND' } else { echo 'MISSING' }" : 
            "docker info 2>/dev/null | grep -A 20 'Insecure Registries:' | grep '" + targetRegistry + "' || echo 'MISSING'";
        String dockerInfoStr = execCommand(dockerInfoCmd2, false).trim();

        if (!dockerInfoStr.contains("MISSING")) {
            System.out.println(GREEN + "[OK] " + targetRegistry + " is already registered in insecure-registries." + RESET);
        } else {
            System.out.println(YELLOW + "[!] " + targetRegistry + " is NOT in insecure-registries. Injecting automatically..." + RESET);
            
            // --- 2. MENGINJEKSI OTOMATIS ---
            String checkJqCmd = IS_WINDOWS ? "echo 'NO_JQ'" : "command -v jq || echo 'NO_JQ'";
            String checkJq = execCommand(checkJqCmd, false).trim();
            
            // Instal jq di Linux jika belum ada (untuk manipulasi JSON)
            if (checkJq.equals("NO_JQ") && !IS_WINDOWS) {
                System.out.println(GRAY + "   > Installing 'jq' for JSON parsing..." + RESET);
                execProcessInherit("sudo apt-get update && sudo apt-get install -y jq");
            }

            String daemonJson = IS_WINDOWS ? System.getProperty("user.home") + "\\.docker\\daemon.json" : "/etc/docker/daemon.json";
            
            if (Files.exists(Paths.get(daemonJson))) {
                // Update file JSON yang sudah ada
                if (IS_WINDOWS) {
                    String psJson = "$j = Get-Content '" + daemonJson + "' -Raw | ConvertFrom-Json; " +
                                    "if (-not $j.'insecure-registries') { Add-Member -InputObject $j -Name 'insecure-registries' -Value @() -MemberType NoteProperty }; " +
                                    "if ($j.'insecure-registries' -notcontains '" + targetRegistry + "') { $j.'insecure-registries' += '" + targetRegistry + "' }; " +
                                    "$j | ConvertTo-Json -Depth 10 | Set-Content '" + daemonJson + "'";
                    execCommand(psJson, false);
                } else {
                    execCommand("sudo jq '. | .\"insecure-registries\" += [\"" + targetRegistry + "\"] | .\"insecure-registries\" |= unique' " + daemonJson + " > /tmp/daemon.json.tmp", false);
                    execCommand("sudo mv /tmp/daemon.json.tmp " + daemonJson, false);
                }
            } else {
                // Buat file JSON baru dari awal jika belum ada
                if (IS_WINDOWS) {
                    execCommand("New-Item -ItemType Directory -Force -Path (Split-Path '" + daemonJson + "') | Out-Null; @{ 'insecure-registries' = @('" + targetRegistry + "') } | ConvertTo-Json -Depth 10 | Set-Content '" + daemonJson + "'", false);
                } else {
                    execCommand("sudo mkdir -p /etc/docker && echo '{ \"insecure-registries\": [\"" + targetRegistry + "\"] }' | sudo tee " + daemonJson, false);
                }
            }
            System.out.println(GREEN + "[OK] Registry URL automatically injected into " + daemonJson + "." + RESET);

            // --- 3. MENERAPKAN PERUBAHAN (RESTART DOCKER) ---
            System.out.println(GRAY + " > Applying changes by restarting Docker daemon..." + RESET);
            
            // Cek apakah Docker berjalan sebagai Native Service (Systemd di Linux / Windows Service)
            String checkDockerServiceCmd = IS_WINDOWS ? 
                "if (Get-Service docker -ErrorAction SilentlyContinue | Where-Object Status -eq 'Running') { echo 'ACTIVE' } else { echo 'INACTIVE' }" : 
                "command -v systemctl >/dev/null && systemctl is-active --quiet docker && echo 'ACTIVE' || echo 'INACTIVE'";
            String isSystemdDockerActive = execCommand(checkDockerServiceCmd, false).trim();
            
            if ("ACTIVE".equals(isSystemdDockerActive)) {
                // Restart otomatis jika native service
                String restartCmd = IS_WINDOWS ? "Restart-Service docker" : "sudo systemctl restart docker";
                execCommand(restartCmd, false);
                System.out.println(GREEN + "[OK] Docker daemon restarted successfully." + RESET);
                
                // Jeda 3 detik agar Docker daemon benar-benar up kembali sebelum script lanjut
                try { Thread.sleep(3000); } catch (InterruptedException e) {} 
            } else {
                // Jika user memakai Docker Desktop, restart tidak bisa ditembak via terminal secara mulus. 
                // Script akan memberitahu user untuk menekan 'Apply & Restart' di GUI.
                System.out.println(RED + "[!] Cannot restart docker automatically (Docker Desktop detected or lacking permissions)." + RESET);
                System.out.println(LIGHT_YELLOW + "[!] ACTION REQUIRED: Please open Docker Desktop GUI -> Settings -> Docker Engine." + RESET);
                System.out.println(LIGHT_YELLOW + "[!] Ensure \"" + targetRegistry + "\" is in the insecure-registries array, then click 'Apply & restart'." + RESET);
                System.out.print("Press [Enter] once you have restarted Docker Desktop...");
                scanner.nextLine();
            }
        }

        // --- STEP 4: Network & Registry Discovery ---
        System.out.println(YELLOW + "[3/7] Discovering Nexus Registry details..." + RESET);
        String settingsContent = new String(Files.readAllBytes(deployM2File));
        
        // Extract mirror URL logic via Regex simulating 'grep -A 5 | grep url'
        String nexusRegistryUrl = "";
        String nexusRegistryPort = "";
        Matcher mirrorMatcher = Pattern.compile("<id>nexus</id>[\\s\\S]{0,200}?<url>http?://([^:/]+):([0-9]+).*?</url>").matcher(settingsContent);
        if (mirrorMatcher.find()) {
            nexusRegistryUrl = mirrorMatcher.group(1);
            nexusRegistryPort = mirrorMatcher.group(2);
            System.out.println(GREEN + "[OK] Nexus URL: " + nexusRegistryUrl + " | Port: " + nexusRegistryPort + RESET);
        } else {
            System.out.println(RED + "[X] CRITICAL ERROR: Invalid/Missing Nexus URL in settings.xml mirror section!" + RESET);
            System.exit(1);
        }

        System.out.println(GRAY + "[*] Fetching Docker NodePort from deployment/secret-k8s.json..." + RESET);
        String kubePort = "";
        Path secretPath = Paths.get(rootDir, deployDir, "secret-k8s.json");
        
        if (Files.exists(secretPath)) {
            String secretContent = new String(Files.readAllBytes(secretPath));
            Matcher portMatcher = Pattern.compile("\"port-nexus-docker\"\\s*:\\s*(\\d+)").matcher(secretContent);
            if (portMatcher.find()) {
                kubePort = portMatcher.group(1);
            }
        }

        if (kubePort.isEmpty()) {
            System.out.println(RED + "[X] CRITICAL ERROR: Could not read 'port-nexus-docker' from secret-k8s.json!" + RESET);
            System.exit(1);
        }

        System.out.println(GREEN + "[OK] Nexus (nexus-docker-service) Credentials" + RESET);

        String nexusRegistryPortDocker = "";
        if (!kubePort.isEmpty()) {
            nexusRegistryPortDocker = kubePort;
            System.out.println(GREEN + "[OK] Nexus Docker Port: " + nexusRegistryPortDocker + " (Live)" + RESET);
        } else {
            System.out.println(RED + "[X] CRITICAL ERROR: Could not fetch 'nexus-docker-service' NodePort from cluster!" + RESET);
            System.exit(1);
        }

        // --- STEP 5: Library Syncing & Nexus Push ---
        System.out.println(YELLOW + "[4/7] Checking and Syncing Maven libraries to Nexus..." + RESET);
        
        String nexusUser = "";
        String nexusPass = "";
        Matcher userMatcher = Pattern.compile("<id>nexus</id>[\\s\\S]{0,200}?<username>(.*?)</username>").matcher(settingsContent);
        if (userMatcher.find()) nexusUser = userMatcher.group(1).trim();
        Matcher passMatcher = Pattern.compile("<id>nexus</id>[\\s\\S]{0,200}?<password>(.*?)</password>").matcher(settingsContent);
        if (passMatcher.find()) nexusPass = passMatcher.group(1).trim();

        List<String> libs = (requiredPackages != null) ? requiredPackages : new ArrayList<>();

        if (libs.isEmpty()) {
            System.out.println(GRAY + "[*] No packages specified for deployment." + RESET);
        }

        if (!libs.isEmpty()) {
            System.out.println(GRAY + "[*] Processing manual install packages..." + RESET);

            for (String lib : libs) {
                // correct format (groupId:artifactId:version)
                String[] parts = lib.trim().split(":");
                if (parts.length < 3) {
                    System.out.println(YELLOW + "    > [!] Invalid format for package: " + lib + " (Expected groupId:artifactId:version). Skipping." + RESET);
                    continue;
                }

                String groupId = parts[0];
                String artifactId = parts[1];
                String version = parts[2];

                String targetRepoName = version.toUpperCase().contains("SNAPSHOT") ? "maven-snapshots" : "maven-releases";
                String dynamicRepoUrl = "http://" + nexusRegistryUrl + ":" + nexusRegistryPort + "/repository/" + targetRepoName;

                String groupPath = groupId.replace(".", "/");
                Path localM2Repo = Paths.get(System.getProperty("user.home"), ".m2", "repository", groupPath, artifactId, version);
                Path pomFile = localM2Repo.resolve(artifactId + "-" + version + ".pom");
                Path jarFile = localM2Repo.resolve(artifactId + "-" + version + ".jar");

                String fileName = "";
                Path localSrc = null;
                if (Files.exists(pomFile)) {
                    fileName = artifactId + "-" + version + ".pom";
                    localSrc = pomFile;
                } else if (Files.exists(jarFile)) {
                    fileName = artifactId + "-" + version + ".jar";
                    localSrc = jarFile;
                } else {
                    System.out.println(RED + "[X] CRITICAL ERROR: Artifact not found (.jar/.pom) for " + artifactId + ":" + version + RESET);
                    System.exit(1);
                }

                Path dest = deployLibsDir.resolve(fileName);
                String nexusJarUrl = dynamicRepoUrl + "/" + groupPath + "/" + artifactId + "/" + version + "/" + fileName;

                String checkUrl = nexusJarUrl;
                if (version.toUpperCase().contains("SNAPSHOT")) {
                    checkUrl = dynamicRepoUrl + "/" + groupPath + "/" + artifactId + "/" + version + "/maven-metadata.xml";
                }

                System.out.println("    > Checking Nexus for " + artifactId + ":" + version + " (Target: " + targetRepoName + ")...");
                int httpStatus = ExecHttp.GET(checkUrl, nexusUser, nexusPass);

                if (httpStatus == 200) {
                    System.out.println("    > " + GREEN + "[OK]" + RESET + " Artifact already exists in Nexus. (Skipping push)");
                    Files.copy(localSrc, dest, StandardCopyOption.REPLACE_EXISTING);
                } else if (httpStatus == 404) {
                    System.out.println("    > " + YELLOW + "[!] Not found in Nexus. Pushing now..." + RESET);
                    Files.copy(localSrc, dest, StandardCopyOption.REPLACE_EXISTING);

                    String tmpDir = System.getProperty("java.io.tmpdir");
                    
                    String mvnDeployCmd = IS_WINDOWS ?
                            "Set-Location \"" + tmpDir + "\"; mvn deploy:deploy-file " :
                            "cd \"" + tmpDir + "\" && mvn deploy:deploy-file ";

                    mvnDeployCmd += "-DgroupId=\"" + groupId + "\" " +
                            "-DartifactId=\"" + artifactId + "\" " +
                            "-Dversion=\"" + version + "\" " +
                            "-Dpackaging=" + (fileName.endsWith(".jar") ? "jar" : "pom") + " " +
                            "-Dfile=\"" + dest.toAbsolutePath() + "\" " +
                            "-DrepositoryId=nexus " +
                            "-Durl=\"" + dynamicRepoUrl + "\" " +
                            "--settings \"" + deployM2File.toAbsolutePath() + "\" -B";

                    int mvnExit = execProcessInherit(mvnDeployCmd);
                    if (mvnExit == 0) {
                        System.out.println("    > " + GREEN + "[OK]" + RESET + " Successfully pushed to Nexus.");
                    } else {
                        System.out.println(RED + "[X] CRITICAL ERROR: Failed to push to Nexus." + RESET);
                        System.exit(1);
                    }
                } else {
                    System.out.println(RED + "[X] CRITICAL ERROR: Unexpected HTTP status " + httpStatus + " from Nexus." + RESET);
                    System.exit(1);
                }
            }
        }

        // --- STEP 6: Containerization ---
        System.out.println(YELLOW + "[5/7] Building and Pushing Docker Image..." + RESET);
        String appIdFinal = appId.toLowerCase();
        String nexusImage = nexusRegistryUrl + ":" + nexusRegistryPortDocker + "/" + appIdFinal + "-app:" + combinedTag;

        System.out.println(GRAY + "[*] Building Docker Image..." + RESET);
        int buildExit = execProcessInherit("docker build --progress=plain --build-arg APP_IDENTITY=\"" + appIdFinal + "\" -t \"" + nexusImage + "\" -f \"" + rootDir + "/Dockerfile\" \"" + rootDir + "\"");
        if (buildExit != 0) {
            System.out.println(RED + "[X] Docker Build Failed!" + RESET);
            System.exit(1);
        }

        System.out.println(GRAY + "[*] Authenticating Docker to Registry..." + RESET);
        String dockerLoginCmd = IS_WINDOWS ? 
            "Write-Output \"" + nexusPass + "\" | docker login " + targetRegistry + " -u \"" + nexusUser + "\" --password-stdin" : 
            "echo \"" + nexusPass + "\" | docker login " + targetRegistry + " -u \"" + nexusUser + "\" --password-stdin";
        int loginExit = execProcessInherit(dockerLoginCmd);
        
        if (loginExit != 0) {
            System.out.println(RED + "[X] Docker Login Failed! Check your Nexus credentials in settings.xml." + RESET);
            System.exit(1);
        }

        System.out.println(GRAY + "[*] Pushing to Registry..." + RESET);
        int pushExit = execProcessInherit("docker push \"" + nexusImage + "\"");
        if (pushExit != 0) {
            System.out.println(RED + "[X] Docker Push Failed! Check insecure-registries in your docker config." + RESET);
            System.exit(1);
        }
        System.out.println(GREEN + "[OK] Image successfully pushed to Nexus." + RESET);

        // --- STEP 7: Orchestration (Helm) ---
        System.out.println(GRAY + "[*] Orchestration (Helm)..." + RESET);

        String helmCmd = "helm";
        String checkHelmCmd = IS_WINDOWS ? 
            "if (Get-Command helm -ErrorAction SilentlyContinue) { echo 'FOUND' } else { echo 'NO_HELM' }" : 
            "command -v helm || echo 'NO_HELM'";
        String checkHelm = execCommand(checkHelmCmd, false).trim();
        
        if ("NO_HELM".equals(checkHelm)) {
            System.out.println(LIGHT_YELLOW + "[*] Global Helm not found. Setting up Portable Helm..." + RESET);
            Path binDir = Paths.get(rootDir, deployDir, ".bin");
            Files.createDirectories(binDir);
            Path helmExe = IS_WINDOWS ? binDir.resolve("windows-amd64/helm.exe") : binDir.resolve("helm");

            if (!Files.exists(helmExe)) {
                System.out.println(CYAN + "[*] Downloading Helm v3.14.3 for " + (IS_WINDOWS ? "Windows" : "Linux") + "..." + RESET);
                
                if (IS_WINDOWS) {
                    String helmUrl = "https://get.helm.sh/helm-v3.14.3-windows-amd64.zip";
                    execCommand("curl.exe -L \"" + helmUrl + "\" -o \"" + binDir + "\\helm.zip\"", false);
                    execCommand("Expand-Archive -Path \"" + binDir + "\\helm.zip\" -DestinationPath \"" + binDir + "\" -Force", false);
                } else {
                    String helmUrl = "https://get.helm.sh/helm-v3.14.3-linux-amd64.tar.gz";
                    execCommand("curl -L \"" + helmUrl + "\" -o \"" + binDir + "/helm.tar.gz\"", false);
                    execCommand("tar -zxvf \"" + binDir + "/helm.tar.gz\" -C \"" + binDir + "\"", false);
                    Files.setPosixFilePermissions(helmExe, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            }

            // if (!Files.exists(helmExe)) {
            //     System.out.println(CYAN + "[*] Downloading Helm v3.14.3 for " + (IS_WINDOWS ? "Windows" : "Linux") + " via Native Java..." + RESET);
                
            //     // --- MATIKAN SSL VERIFICATION SEMENTARA ---
            //     ExecHttp.disableSslVerification();
                

            //     if (IS_WINDOWS) {
            //         String helmUrl = "https://get.helm.sh/helm-v3.14.3-windows-amd64.zip";
            //         Path zipPath = binDir.resolve("helm.zip");
            //         try (InputStream in = new URL(helmUrl).openStream()) {
            //             Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
            //         }
            //         execCommand("Expand-Archive -Path \"" + zipPath.toString() + "\" -DestinationPath \"" + binDir.toString() + "\" -Force", false);
            //     } else {
            //         String helmUrl = "https://get.helm.sh/helm-v3.14.3-linux-amd64.tar.gz";
            //         Path tarPath = binDir.resolve("helm.tar.gz");
            //         try (InputStream in = new URL(helmUrl).openStream()) {
            //             Files.copy(in, tarPath, StandardCopyOption.REPLACE_EXISTING);
            //         }
            //         execCommand("tar -zxvf \"" + tarPath.toString() + "\" -C \"" + binDir.toString() + "\"", false);
            //         Files.setPosixFilePermissions(helmExe, PosixFilePermissions.fromString("rwxr-xr-x"));
            //     }
            // }

            helmCmd = helmExe.toAbsolutePath().toString();
        }

        System.out.println(YELLOW + "[6/7] Configuring Kubernetes Cluster..." + RESET);
        String appIdClean = appIdFinal.replace("_", "-");
        String releaseName = appIdClean;

        if (nexusUser.isEmpty()) {
            System.out.println(RED + "[X] CRITICAL ERROR: Nexus credentials missing in settings.xml!" + RESET);
            System.exit(1);
        }

        System.out.println(GRAY + "[*] Syncing ImagePullSecret..." + RESET);
        String secretCmd = "kubectl create secret docker-registry nexus-docker-secret " +
                "--docker-server=\"" + nexusRegistryUrl + ":" + nexusRegistryPortDocker + "\" " +
                "--docker-username=\"" + nexusUser + "\" --docker-password=\"" + nexusPass + "\" " +
                "--dry-run=client -o yaml | kubectl apply -f -";
        execCommand(secretCmd, false);

        System.out.println(CYAN + "[*] helm fetch reposotory helm-internal ..." + RESET);
        execCommand(helmCmd + " repo add bcalife-helm \"http://" + nexusRegistryUrl + ":" + nexusRegistryPort + "/repository/helm-internal/\" --username \"" + nexusUser + "\" --password \"" + nexusPass + "\"", false);
        execCommand(helmCmd + " repo update", false);

        System.out.println(YELLOW + "[7/7] Executing Helm Upgrade/Install..." + RESET);

        String appDomain = "jkt-ho-svr-213";
        String ingressContent = "routing:\n" +
                "  mode: path\n" +
                "  domain: \"" + appDomain + "\"\n\n" +
                "ingress:\n" +
                "  enabled: true\n" +
                "  className: nginx\n\n" +
                "  annotations:\n" +
                "    nginx.ingress.kubernetes.io/proxy-body-size: \"500m\"\n" +
                "    nginx.ingress.kubernetes.io/affinity: \"cookie\"\n" +
                "    nginx.ingress.kubernetes.io/session-cookie-name: \"route-" + appIdClean + "\"\n" +
                "    nginx.ingress.kubernetes.io/session-cookie-hash: \"sha1\"\n" +
                "    nginx.ingress.kubernetes.io/session-cookie-path: \"/" + appIdFinal + "\"\n" +
                "    nginx.ingress.kubernetes.io/rewrite-target: /" + appIdFinal + "$1$2\n";
        
        Path ingressYaml = Paths.get(rootDir, "ingress-override.yaml");
        Files.write(ingressYaml, ingressContent.getBytes());

        System.out.println(CYAN + "[*] Helm CLI Version:" + RESET);
        execProcessInherit(helmCmd + " version --short");

        System.out.println(CYAN + "[*] Helm Chart Latest Version:" + RESET);
        String searchRepoCmd = IS_WINDOWS ?
            helmCmd + " search repo bcalife-helm/universal-platform | Select-Object -First 2" :
            helmCmd + " search repo bcalife-helm/universal-platform | head -n 2";
        execProcessInherit(searchRepoCmd);

        // Path envFile = Paths.get(rootDir, "src", "main", "resources", ".env");
        List<String> helmEnvArgs = new ArrayList<>();

        Optional<Path> optionalEnv = EnvLocator.find(rootDir);

        System.out.println(GRAY + "[*] Preparing Environment (.env) Variables for Helm..." + RESET);

        if (optionalEnv.isPresent()) {
            Path envFile = optionalEnv.get();
            System.out.println(GREEN + "[OK] .env file found at: " + envFile.toAbsolutePath() + RESET);
            System.out.println(GRAY + "[*] Injecting to Helm..." + RESET);
            
            List<String> lines = Files.readAllLines(envFile);
            for (String line : lines) {
                if (line.contains("=") && !line.trim().startsWith("#")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim().replaceAll("^\"|\"$|^'|'$", "");
                    helmEnvArgs.add("--set");
                    helmEnvArgs.add("env." + key + "=" + val);
                }
            }
        } else {
            System.out.println(YELLOW + "[!] .env file completely not found anywhere! Using fallback defaults." + RESET);
            helmEnvArgs.addAll(Arrays.asList(
                    "--set", "env.APP_ENV=dev",
                    "--set", "env.CONSUL_HOST=10.1.40.240",
                    "--set", "env.CONSUL_PORT=8500"
            ));
        }

        System.out.println(CYAN + "[*] deploy image " + combinedTag + " ..." + RESET);
        
        List<String> helmUpgradeCmd = new ArrayList<>(Arrays.asList(
                helmCmd, "upgrade", "--install", releaseName, "bcalife-helm/universal-platform",
                "--set", "image.repository=" + nexusRegistryUrl + ":" + nexusRegistryPortDocker + "/" + appIdFinal + "-app",
                "--set", "image.tag=" + combinedTag,
                "--set", "routing.mode=path",
                "--set", "fullnameOverride=" + appIdClean + "-app",
                "--set", "replicaCount=" + replicaCountStr,
                "--set", "service.type=ClusterIP",
                "--set", "autoscaling.enabled=false",
                "--set", "healthcheck.enabled=false",
                "--set", "canary.enabled=false",
                "--set", "tls.enabled=false"
        ));
        
        helmUpgradeCmd.addAll(helmEnvArgs);

        helmUpgradeCmd.addAll(Arrays.asList(
                "-f", ingressYaml.toAbsolutePath().toString(),
                "--wait", "--timeout", "5m"
        ));

        int helmExit = execProcessBuilderInherit(helmUpgradeCmd);
        Files.deleteIfExists(ingressYaml);

        if (helmExit == 0) {
            System.out.println("\n" + GREEN + "====================================================" + RESET);
            System.out.println(GREEN + "         DEPLOYMENT FINISHED SUCCESSFULLY!          " + RESET);
            System.out.println(GREEN + "====================================================" + RESET);

            execCommand("kubectl rollout restart deployment/\"" + appIdClean + "-app\"", false);

            System.out.println("\n" + CYAN + "APPLICATION INFORMATION" + RESET);
            System.out.println("----------------------------------------------------");
            System.out.println("App Name        : " + YELLOW + appIdClean + RESET);
            System.out.println("Docker Image    : " + YELLOW + nexusImage + RESET);
            System.out.println("Helm Release    : " + YELLOW + releaseName + RESET);
            System.out.println("Replicas        : " + YELLOW + replicaCountStr + RESET);

            System.out.println("\n" + CYAN + "ACCESS URL" + RESET);
            System.out.println("----------------------------------------------------");
            System.out.println("Application URL : " + GREEN + "http://" + appDomain + ":30001/" + appIdFinal + RESET);

            System.out.println("\n" + CYAN + "KUBERNETES RESOURCES" + RESET);
            System.out.println("----------------------------------------------------");

            System.out.println("\nPods:");
            execProcessInherit("kubectl get pods -l app.kubernetes.io/name=\"" + appIdClean + "-app\"");

            System.out.println("\nService:");
            execProcessInherit("kubectl get svc \"" + appIdClean + "-app\"");

            System.out.println("\nIngress:");
            execProcessInherit("kubectl get ingress \"" + appIdClean + "-app\"");

            System.out.println("\n" + CYAN + "USEFUL COMMANDS" + RESET);
            System.out.println("----------------------------------------------------");
            System.out.println("View logs:");
            System.out.println("kubectl logs -f deployment/" + appIdClean + "-app\n");
            System.out.println("Restart deployment:");
            System.out.println("kubectl rollout restart deployment/" + appIdClean + "-app\n");
            System.out.println("Describe pod:");
            System.out.println("kubectl describe pod -l app.kubernetes.io/name=" + appIdClean + "-app");
            System.out.println("----------------------------------------------------");

        } else {
            System.out.println("\n" + RED + "[!] DEPLOYMENT FAILED OR TIMED OUT!" + RESET);
        }
        
        scanner.close();
    }

    private static String execCommand(String command, boolean printError) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = IS_WINDOWS ?
                new ProcessBuilder("powershell.exe", "-Command", command) :
                new ProcessBuilder("bash", "-c", command);
            
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            if (printError) {
                BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while ((line = errReader.readLine()) != null) {
                    System.err.println(line);
                }
            }
            
            System.out.println(GRAY + "[execute] " + String.join(" ", command) + RESET + "\n");
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output.toString();
    }

    private static int execProcessInherit(String command) {
        try {
            System.out.println(GRAY + "[execute] " + String.join(" ", command) + RESET + "\n");
            ProcessBuilder pb = IS_WINDOWS ?
                new ProcessBuilder("powershell.exe", "-Command", command) :
                new ProcessBuilder("bash", "-c", command);
            
            Process p = pb.inheritIO().start();
            return p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }
    
    private static int execProcessBuilderInherit(List<String> commandList) {
        try {
            System.out.println(GRAY + "[execute] " + String.join(" ", commandList) + RESET + "\n");
            Process p = new ProcessBuilder(commandList).inheritIO().start();
            return p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    private static String getProjectIdFromPom() {
        try {
            Path pomPath = Paths.get("pom.xml");

            if (!Files.exists(pomPath)) return "UNKNOWN";

            String pom = new String(Files.readAllBytes(pomPath));

            // PRIORITAS 1: parent artifactId
            Matcher parentMatcher = Pattern.compile(
                "<parent>[\\s\\S]*?<artifactId>(.*?)</artifactId>"
            ).matcher(pom);

            if (parentMatcher.find()) {
                return parentMatcher.group(1).trim();
            }

            // PRIORITAS 2: artifactId project sendiri
            Matcher artifactMatcher = Pattern.compile(
                "<artifactId>(.*?)</artifactId>"
            ).matcher(pom);

            if (artifactMatcher.find()) {
                return artifactMatcher.group(1).trim();
            }

        } catch (Exception e) {
            System.out.println("[WARN] Failed read pom.xml: " + e.getMessage());
        }

        return "UNKNOWN";
    }
}