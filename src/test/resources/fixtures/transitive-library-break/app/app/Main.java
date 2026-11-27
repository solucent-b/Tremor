package app;

public class Main {
    public static void main(String[] args) {
        System.out.println(lib.Strings.nullToEmpty(args.length > 0 ? args[0] : null));
        System.out.println(y.Validator.valid("x"));
    }
}
