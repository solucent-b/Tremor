package app;

import lib.Store;

public class Settings {
    public void save(Store store) {
        store.put("theme", "dark");
    }
}
