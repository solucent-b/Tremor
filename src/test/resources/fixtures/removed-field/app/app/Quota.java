package app;

public class Quota {
    public boolean allowed(int users) {
        return users <= lib.Limits.maxUsers;
    }
}
