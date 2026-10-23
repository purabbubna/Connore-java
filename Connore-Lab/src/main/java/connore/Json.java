package connore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP support code. The application's feature methods use ordinary maps/lists. */
public final class Json {
    private Json() { }

    public static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]);
        return result;
    }

    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        StringBuffer text = new StringBuffer();
        if (value instanceof Map) {
            text.append('{');
            boolean first = true;
            Map<?, ?> object = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!first) text.append(',');
                first = false;
                text.append(stringify(entry.getKey().toString())).append(':').append(stringify(entry.getValue()));
            }
            return text.append('}').toString();
        }
        if (value instanceof Iterable) {
            text.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) text.append(',');
                first = false;
                text.append(stringify(item));
            }
            return text.append(']').toString();
        }
        text.append('"');
        String string = value.toString();
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (c == '"' || c == '\\') text.append('\\').append(c);
            else if (c == '\n') text.append("\\n");
            else if (c == '\r') text.append("\\r");
            else if (c == '\t') text.append("\\t");
            else if (c < 32) text.append(String.format("\\u%04x", (int) c));
            else text.append(c);
        }
        return text.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) throws AppException {
        Parser parser = new Parser(text);
        Object value = parser.read(0);
        parser.spaces();
        AppException.require(parser.position == text.length() && value instanceof Map, 400, "INVALID_JSON");
        return (Map<String, Object>) value;
    }

    // Assignment 6: an inner helper class keeps parser details inside Json.
    private static class Parser {
        private final String source;
        private int position;

        Parser(String source) { this.source = source; }

        void spaces() {
            while (position < source.length() && " \n\r\t".indexOf(source.charAt(position)) >= 0) position++;
        }

        char take() throws AppException {
            AppException.require(position < source.length(), 400, "INVALID_JSON");
            return source.charAt(position++);
        }

        Object read(int depth) throws AppException {
            AppException.require(depth <= 30, 400, "INVALID_JSON");
            spaces();
            AppException.require(position < source.length(), 400, "INVALID_JSON");
            char c = source.charAt(position);
            if (c == '"') return string();
            if (c == '{') {
                position++;
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                spaces();
                if (position < source.length() && source.charAt(position) == '}') { position++; return result; }
                while (true) {
                    spaces();
                    AppException.require(position < source.length() && source.charAt(position) == '"', 400, "INVALID_JSON");
                    String key = string();
                    spaces();
                    AppException.require(take() == ':' && !result.containsKey(key), 400, "INVALID_JSON");
                    result.put(key, read(depth + 1));
                    spaces();
                    char end = take();
                    if (end == '}') return result;
                    AppException.require(end == ',', 400, "INVALID_JSON");
                }
            }
            if (c == '[') {
                position++;
                ArrayList<Object> result = new ArrayList<Object>();
                spaces();
                if (position < source.length() && source.charAt(position) == ']') { position++; return result; }
                while (true) {
                    result.add(read(depth + 1));
                    spaces();
                    char end = take();
                    if (end == ']') return result;
                    AppException.require(end == ',', 400, "INVALID_JSON");
                }
            }
            if (source.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
            if (source.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
            if (source.startsWith("null", position)) { position += 4; return null; }
            int start = position;
            while (position < source.length() && "-+0123456789.eE".indexOf(source.charAt(position)) >= 0) position++;
            String number = source.substring(start, position);
            AppException.require(number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), 400, "INVALID_JSON");
            try {
                if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                    Double result = Double.valueOf(number);
                    AppException.require(Double.isFinite(result.doubleValue()), 400, "INVALID_JSON");
                    return result;
                }
                return Long.valueOf(number);
            } catch (NumberFormatException exception) { throw new AppException(400, "INVALID_JSON"); }
        }

        String string() throws AppException {
            AppException.require(take() == '"', 400, "INVALID_JSON");
            StringBuffer result = new StringBuffer();
            while (true) {
                char c = take();
                if (c == '"') return result.toString();
                AppException.require(c >= 32, 400, "INVALID_JSON");
                if (c != '\\') { result.append(c); continue; }
                char escape = take();
                if (escape == '"' || escape == '\\' || escape == '/') result.append(escape);
                else if (escape == 'b') result.append('\b');
                else if (escape == 'f') result.append('\f');
                else if (escape == 'n') result.append('\n');
                else if (escape == 'r') result.append('\r');
                else if (escape == 't') result.append('\t');
                else if (escape == 'u') {
                    AppException.require(position + 4 <= source.length(), 400, "INVALID_JSON");
                    try { result.append((char) Integer.parseInt(source.substring(position, position + 4), 16)); }
                    catch (NumberFormatException exception) { throw new AppException(400, "INVALID_JSON"); }
                    position += 4;
                } else throw new AppException(400, "INVALID_JSON");
            }
        }
    }
}
