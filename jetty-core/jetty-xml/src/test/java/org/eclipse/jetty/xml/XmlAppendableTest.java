//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
