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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.Iterator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HeaderPatternRuleTest extends AbstractRuleTestCase
{
    private HeaderPatternRule _rule;

    @BeforeEach
    public void init() throws Exception
    {
        start(false);
        _rule = new HeaderPatternRule();
        _rule.setPattern("*");
    }

    @Test
    public void testHeaderWithTextValues() throws IOException
    {
        // different keys
        String[][] headers = {
            {"hnum#1", "test1"},
            {"hnum#2", "2test2"},
            {"hnum#3", "test3"}
        };
        assertHeaders(headers);
    }

    @Test
    public void testHeaderWithNumberValues() throws IOException
    {
        String[][] headers = {
            {"hello", "1"},
            {"hello", "-1"},
            {"hello", "100"},
            {"hello", "100"},
            {"hello", "100"},
            {"hello", "100"},
            {"hello", "100"},

            {"hello1", "200"}
        };
        assertHeaders(headers);
    }

    @Test
    public void testHeaderOverwriteValues() throws IOException
    {
        String[][] headers = {
            {"size", "100"},
            {"size", "200"},
            {"size", "300"},
            {"size", "400"},
            {"size", "500"},
            {"title", "abc"},
            {"title", "bac"},
            {"title", "cba"},
            {"title1", "abba"},
            {"title1", "abba1"},
            {"title1", "abba"},
            {"title1", "abba1"}
        };
        assertHeaders(headers);

        Iterator<String> e = _response.getHeaders("size").iterator();
        int count = 0;
        while (e.hasNext())
        {
            e.next();
            count++;
        }

        assertEquals(1, count);
        assertEquals("500", _response.getHeader("size"));
        assertEquals("cba", _response.getHeader("title"));
        assertEquals("abba1", _response.getHeader("title1"));
    }

    private void assertHeaders(String[][] headers) throws IOException
    {
        for (String[] header : headers)
        {
            _rule.setName(header[0]);
            _rule.setValue(header[1]);

            _rule.apply(null, _request, _response);

            assertEquals(header[1], _response.getHeader(header[0]));
        }
    }
}
