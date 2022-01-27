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

package org.eclipse.jetty.websocket.core.extensions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExtensionConfigTest
{
    private void assertConfig(ExtensionConfig cfg, String expectedName, Map<String, String> expectedParams)
    {
        String prefix = "ExtensionConfig";
        assertThat(prefix + ".Name", cfg.getName(), is(expectedName));

        prefix += ".getParameters()";
        Map<String, String> actualParams = cfg.getParameters();
        assertThat(prefix, actualParams, notNullValue());
        assertThat(prefix + ".size", actualParams.size(), is(expectedParams.size()));

        for (String expectedKey : expectedParams.keySet())
        {
            assertThat(prefix + ".containsKey(" + expectedKey + ")", actualParams.containsKey(expectedKey), is(true));

            String expectedValue = expectedParams.get(expectedKey);
            String actualValue = actualParams.get(expectedKey);

            assertThat(prefix + ".containsKey(" + expectedKey + ")", actualValue, is(expectedValue));
        }
    }

    @Test
    public void testParseMuxExample()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("mux; max-channels=4; flow-control");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("max-channels", "4");
        expectedParams.put("flow-control", null);
        assertConfig(cfg, "mux", expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample1()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=foo");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method", "foo");
        assertConfig(cfg, "permessage-compress", expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample2()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo; x=10\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method", "foo; x=10");
        assertConfig(cfg, "permessage-compress", expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample3()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo, bar\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method", "foo, bar");
        assertConfig(cfg, "permessage-compress", expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample4()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo; use_x, foo\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method", "foo; use_x, foo");
        assertConfig(cfg, "permessage-compress", expectedParams);
    }

    @Test
    public void testParsePerMessageCompressExample5()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("permessage-compress; method=\"foo; x=\\\"Hello World\\\", bar\"");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("method", "foo; x=\"Hello World\", bar");
        assertConfig(cfg, "permessage-compress", expectedParams);
    }

    @Test
    public void testParseSimpleBasicParameters()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("bar; baz=2");
        Map<String, String> expectedParams = new HashMap<>();
        expectedParams.put("baz", "2");
        assertConfig(cfg, "bar", expectedParams);
    }

    @Test
    public void testParseSimpleNoParameters()
    {
        ExtensionConfig cfg = ExtensionConfig.parse("foo");
        Map<String, String> expectedParams = new HashMap<>();
        assertConfig(cfg, "foo", expectedParams);
    }

    @Test
    public void testParseListSimple()
    {
        String[] rawHeaders = new String[]{
            "permessage-compress; client_max_window_bits",
            "capture; output=\"wscapture.log\"",
            "identity"
        };

        List<ExtensionConfig> configs = ExtensionConfig.parseList(rawHeaders);
        assertThat("Configs", configs.size(), is(3));
        assertThat("Configs[0]", configs.get(0).getName(), is("permessage-compress"));
        assertThat("Configs[1]", configs.get(1).getName(), is("capture"));
        assertThat("Configs[2]", configs.get(2).getName(), is("identity"));
    }

    /**
     * Parse a list of headers from a client that isn't following the RFC spec properly,
     * where they include multiple extensions in 1 header.
     */
    @Test
    public void testParseListUnsplit()
    {
        String[] rawHeaders = new String[]{
            "permessage-compress; client_max_window_bits, identity",
            "capture; output=\"wscapture.log\""
        };

        List<ExtensionConfig> configs = ExtensionConfig.parseList(rawHeaders);
        assertThat("Configs", configs.size(), is(3));
        assertThat("Configs[0]", configs.get(0).getName(), is("permessage-compress"));
        assertThat("Configs[1]", configs.get(1).getName(), is("identity"));
        assertThat("Configs[2]", configs.get(2).getName(), is("capture"));
    }

    @Test
    public void testParseNoExtensions()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExtensionConfig.parse("=params"));
        assertThat(error.getMessage(), containsString("contains no ExtensionConfigs"));
    }

    @Test
    public void testParseMultipleExtensions()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExtensionConfig.parse("ext1;param1, ext2;param2"));
        assertThat(error.getMessage(), containsString("contains multiple ExtensionConfigs"));
    }

    @Test
    public void testParseMultipleExtensionsSameName()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExtensionConfig.parse("ext1;paramOption1, ext1;paramOption2"));
        assertThat(error.getMessage(), containsString("contains multiple ExtensionConfigs"));
    }
}
