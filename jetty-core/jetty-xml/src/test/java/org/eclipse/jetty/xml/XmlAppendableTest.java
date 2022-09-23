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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class XmlAppendableTest
{
    @Test
    public void test() throws Exception
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XmlAppendable out = new XmlAppendable(outputStream);
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
            """
                <?xml version="1.0" encoding="utf-8"?>
                <test>
                  <tag/>
                  <tag name="attr value" noval="" quotes="&apos;&quot;"/>
                  <tag name="attr value" noval="" quotes="&apos;&quot;">content</tag>
                  <level1>
                    <tag>content</tag>
                    <tag>content</tag>
                  </level1>
                  <level1 name="attr value" noval="" quotes="&apos;&quot;">
                    <level2>
                      <tag>content</tag>
                      <tag>content</tag>
                    </level2>
                  </level1>
                </test>
                """;

        String result = outputStream.toString(StandardCharsets.UTF_8);
        assertEquals(expected, result);
    }
}
