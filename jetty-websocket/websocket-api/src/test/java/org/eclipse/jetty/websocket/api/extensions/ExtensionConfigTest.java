//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api.extensions;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class ExtensionConfigTest
{
    private void assertConfig(ExtensionConfig cfg, String expectedName, Map<String, String> expectedParams)
    {
        String prefix = "ExtensionConfig";
        Assert.assertThat(prefix + ".Name",cfg.getName(),is(expectedName));

        prefix += ".getParameters()";
        Map<String, String> actualParams = cfg.getParameters();
        Assert.assertThat(prefix,actualParams,notNullValue());
        Assert.assertThat(prefix + ".size",actualParams.size(),is(expectedParams.size()));

        for (String expectedKey : expectedParams.keySet())
        {
            Assert.assertThat(prefix + ".containsKey(" + expectedKey + ")",actualParams.containsKey(expectedKey),is(true));

            String expectedValue = expectedParams.get(expectedKey);
            String actualValue = actualParams.get(expectedKey);

            Assert.assertThat(prefix + ".containsKey(" + expectedKey + ")",actualValue,is(expectedValue));
        }
    }

    @Test
    public void testParseMuxExample()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("mux; max-channels=4; flow-control");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("max-channels","4");
        expectedParams.put("flow-control",null);
        assertConfig(cfg,"mux",expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample1()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=foo");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method","foo");
        assertConfig(cfg,"permessage-compress",expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample2()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo; x=10\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method","foo; x=10");
        assertConfig(cfg,"permessage-compress",expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample3()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo, bar\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method","foo, bar");
        assertConfig(cfg,"permessage-compress",expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample4()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo; use_x, foo\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method","foo; use_x, foo");
        assertConfig(cfg,"permessage-compress",expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample5()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo; x=\\\"Hello World\\\", bar\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method","foo; x=\"Hello World\", bar");
        assertConfig(cfg,"permessage-compress",expectedParams);
    }

    @Test
    public void testParseSimple_BasicParameters()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("bar; baz=2");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("baz","2");
        assertConfig(cfg,"bar",expectedParams);
    }

    @Test
    public void testParseSimple_NoParameters()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("foo");
        Map<String, String> expectedParams = new HashMap<>();
        assertConfig(cfg,"foo",expectedParams);
    }
}
