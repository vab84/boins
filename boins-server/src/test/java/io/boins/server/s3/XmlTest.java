package io.boins.server.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlTest {

    @Test
    void escapesSpecialCharacters() {
        assertEquals("a&amp;b &lt;tag&gt; &quot;q&quot; &apos;s&apos;", Xml.escape("a&b <tag> \"q\" 's'"));
        assertEquals("plain", Xml.escape("plain"));
    }

    @Test
    void buildsNestedElements() {
        String xml = new Xml().openRoot("Root")
                .element("Key", "a<b")
                .open("Child").element("Value", 42).close("Child")
                .element("Skipped", null)
                .close("Root")
                .toString();
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<Root xmlns=\"" + Xml.S3_NS + "\">"));
        assertTrue(xml.contains("<Key>a&lt;b</Key>"));
        assertTrue(xml.contains("<Child><Value>42</Value></Child>"));
        assertTrue(!xml.contains("Skipped"));
    }

    @Test
    void errorBodyContainsAllFields() {
        String xml = Xml.error("NoSuchKey", "The key does not exist", "/bucket/key", "req-1");
        assertTrue(xml.contains("<Code>NoSuchKey</Code>"));
        assertTrue(xml.contains("<Message>The key does not exist</Message>"));
        assertTrue(xml.contains("<Resource>/bucket/key</Resource>"));
        assertTrue(xml.contains("<RequestId>req-1</RequestId>"));
    }
}
