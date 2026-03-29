package com.bcalife.common.deploy.core;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

public class UI {

    // KODE WARNA ANSI
    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String CYAN = "\033[36m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED = "\033[31m";
    public static final String WHITE = "\033[97m";
    public static final String GRAY = "\033[90m";

    private static boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    static {
        // Force UTF-8 untuk Windows agar simbol muncul jika Terminal mendukung (Windows Terminal)
        if (isWindows) {
            try {
                System.setOut(new PrintStream(System.out, true, "UTF-8"));
            } catch (UnsupportedEncodingException ignored) {}
        }
    }

    // Mendapatkan Icon yang Aman (Jika Windows tampilkan teks, jika Linux tampilkan Emoji)
    private static String getIcon(String emoji, String fallback) {
        return isWindows ? fallback : emoji;
    }

    public static void clearScreen() {
        if (!isWindows) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } else {
            // Di Windows CMD/PS clear screen ANSI sering bikin crash Scanner
            System.out.println("\n\n"); 
        }
    }

    public static void printHeader(String title) {
        System.out.println(CYAN + "====================================================" + RESET);
        System.out.println(CYAN + BOLD + "           " + title.toUpperCase() + RESET);
        System.out.println(CYAN + "====================================================" + RESET);
    }

    public static void printStep(String step, String title) {
        String icon = getIcon("📦 ", "[STEP] ");
        System.out.println("\n" + WHITE + BOLD + "[" + step + "] " + icon + title + RESET);
        System.out.println(GRAY + "----------------------------------------------------" + RESET);
    }

    public static void printInfo(String msg) {
        System.out.println(GRAY + " [*] " + RESET + msg);
    }

    public static void printSuccess(String msg) {
        String icon = getIcon("✅ ", "[OK] ");
        System.out.println(GREEN + " " + icon + RESET + msg);
    }

    public static void printError(String msg) {
        String icon = getIcon("❌ ", "[ERR] ");
        System.out.println(RED + " " + icon + "CRITICAL ERROR: " + RESET + msg);
    }

    public static void printWarning(String msg) {
        String icon = getIcon("⚠️  ", "[!] ");
        System.out.println(YELLOW + " " + icon + RESET + msg);
    }
    
    public static void printServer(String msg) {
        String icon = getIcon("📡 ", "[SRV] ");
        System.out.println(BOLD + icon + msg + RESET);
    }

    public static void printProfile(String msg) {
        String icon = getIcon("📦 ", "[PRO] ");
        System.out.println(BOLD + icon + msg + RESET);
    }
}