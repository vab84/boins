package io.boins.server.s3;

/**
 * Minimal XML builder for S3 responses. S3 XML bodies are small and flat, so a string
 * builder with proper escaping beats a full XML library dependency.
 */
public final class Xml {

    public static final String S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private final StringBuilder sb = new StringBuilder(512);

    public Xml() {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    }

    public Xml open(String tag) {
        sb.append('<').append(tag).append('>');
        return this;
    }

    /** Opens the root element with the S3 namespace. */
    public Xml openRoot(String tag) {
        sb.append('<').append(tag).append(" xmlns=\"").append(S3_NS).append("\">");
        return this;
    }

    public Xml close(String tag) {
        sb.append("</").append(tag).append('>');
        return this;
    }

    /** Appends {@code <tag>escaped-value</tag>}; skips the element entirely for null values. */
    public Xml element(String tag, Object value) {
        if (value != null) {
            sb.append('<').append(tag).append('>')
                    .append(escape(String.valueOf(value)))
                    .append("</").append(tag).append('>');
        }
        return this;
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    public static String escape(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String replacement = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&apos;";
                default -> null;
            };
            if (replacement != null) {
                if (out == null) {
                    out = new StringBuilder(s.length() + 16).append(s, 0, i);
                }
                out.append(replacement);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    /** Renders the standard S3 error body. */
    public static String error(String code, String message, String resource, String requestId) {
        return new Xml()
                .open("Error")
                .element("Code", code)
                .element("Message", message)
                .element("Resource", resource)
                .element("RequestId", requestId)
                .close("Error")
                .toString();
    }
}
