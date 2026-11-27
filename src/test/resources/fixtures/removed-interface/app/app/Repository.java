package app;

import java.io.Closeable;
import java.io.IOException;

public class Repository {
    public void use() throws IOException {
        try (Closeable c = new lib.Connection()) {
            System.out.println(c);
        }
    }
}
