package lib;

public class Strings {
    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
