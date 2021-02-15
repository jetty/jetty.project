//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.xml;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class XmlAppendableTest
{
    @Test
    public void test() throws Exception
    {
        StringBuilder b = new StringBuilder();
        XmlAppendable out = new XmlAppendable(b);
        Map<String, String> attr = new LinkedHashMap<>();

        out.openTag("test");

        attr.put("name", "attr value");
        attr.put("noval", null);
        attr.put("quotes", "'\"");

        out.tag("tag");
        out.tag("tag", attr);
        out.tag("tag", attr, "content");

        out.openTag("level1").tag("tag", "content").tag("tag", "content").closeTag();
        out.openTag("level1", attr).openTag("level2").tag("tag", "content").tag("tag", "content").closeTag().closeTag();

        out.closeTag();

        String expected =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<test>\n" +
                "  <tag/>\n" +
                "  <tag name=\"attr value\" noval=\"\" quotes=\"&apos;&quot;\"/>\n" +
                "  <tag name=\"attr value\" noval=\"\" quotes=\"&apos;&quot;\">content</tag>\n" +
                "  <level1>\n" +
                "    <tag>content</tag>\n" +
                "    <tag>content</tag>\n" +
                "  </level1>\n" +
                "  <level1 name=\"attr value\" noval=\"\" quotes=\"&apos;&quot;\">\n" +
                "    <level2>\n" +
                "      <tag>content</tag>\n" +
                "      <tag>content</tag>\n" +
                "    </level2>\n" +
                "  </level1>\n" +
                "</test>\n";
        assertEquals(expected, b.toString());
    }
}
