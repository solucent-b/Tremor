package app;

public class Reader {
    public String read(lib.Parser parser, String text) {
        return parser.tokenize(text) + parser.parse(text);
    }
}
