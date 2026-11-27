package lib;

public interface Plugin {
    void start();

    void stop();

    default String name() {
        return "plugin";
    }
}
