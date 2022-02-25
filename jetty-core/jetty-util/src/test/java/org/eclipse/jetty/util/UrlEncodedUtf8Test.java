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

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

public class UrlEncodedUtf8Test
{
    private static final Logger LOG = LoggerFactory.getLogger(UrlEncodedUtf8Test.class);

    @Test
    public void testIncompleteSequestAtTheEnd() throws Exception
    {
        byte[] bytes = {97, 98, 61, 99, -50};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String expected = "c" + Utf8Appendable.REPLACEMENT;

        fromString(test, test, "ab", expected, false);
        fromInputStream(test, bytes, "ab", expected, false);
    }

    @Test
    public void testIncompleteSequestAtTheEnd2() throws Exception
    {
        byte[] bytes = {97, 98, 61, -50};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String expected = "" + Utf8Appendable.REPLACEMENT;

        fromString(test, test, "ab", expected, false);
        fromInputStream(test, bytes, "ab", expected, false);
    }

    @Test
    public void testIncompleteSequestInName() throws Exception
    {
        byte[] bytes = {101, -50, 61, 102, 103, 38, 97, 98, 61, 99, 100};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String name = "e" + Utf8Appendable.REPLACEMENT;
        String value = "fg";

        fromString(test, test, name, value, false);
        fromInputStream(test, bytes, name, value, false);
    }

    @Test
    public void testIncompleteSequestInValue() throws Exception
    {
        byte[] bytes = {101, 102, 61, 103, -50, 38, 97, 98, 61, 99, 100};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String name = "ef";
        String value = "g" + Utf8Appendable.REPLACEMENT;

        fromString(test, test, name, value, false);
        fromInputStream(test, bytes, name, value, false);
    }

    // TODO: Split thrown/not-thrown
    static void fromString(String test, String s, String field, String expected, boolean thrown) throws Exception
    {
        MultiMap<String> values = new MultiMap<>();
        try
        {
            UrlEncoded.decodeUtf8To(s, 0, s.length(), values);
            if (thrown)
                fail("Expected an exception");
            assertThat(test, values.getString(field), is(expected));
        }
        catch (Exception e)
        {
            if (!thrown)
                throw e;
            LOG.trace("IGNORED", e);
        }
    }

    // TODO: Split thrown/not-thrown
    static void fromInputStream(String test, byte[] b, String field, String expected, boolean thrown) throws Exception
    {
        InputStream is = new ByteArrayInputStream(b);
        MultiMap<String> values = new MultiMap<>();
        try
        {
            UrlEncoded.decodeUtf8To(is, values, 1000000, -1);
            if (thrown)
                fail("Expected an exception");
            assertThat(test, values.getString(field), is(expected));
        }
        catch (Exception e)
        {
            if (!thrown)
                throw e;
            LOG.trace("IGNORED", e);
        }
    }
}
