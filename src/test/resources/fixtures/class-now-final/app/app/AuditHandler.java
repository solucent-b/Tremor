package app;

public class AuditHandler extends lib.Handler {
    @Override
    public void handle(String event) {
        System.out.println(event);
    }
}
