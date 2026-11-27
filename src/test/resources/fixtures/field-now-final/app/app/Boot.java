package app;

public class Boot {
    public static void main(String[] args) {
        lib.Config.region = "us";
        System.out.println(lib.Config.region);
    }
}
