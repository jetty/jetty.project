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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PatternRuleTest
{
    private PatternRule _rule;

    @BeforeEach
    public void init()
    {
        _rule = new TestPatternRule();
    }

    @AfterEach
    public void destroy()
    {
        _rule = null;
    }

    @Test
    public void testTrueMatch() throws IOException
    {
        String[][] matchCases = {
            // index 0 - pattern
            // index 1 - URI to match

            {"/abc", "/abc"},
            {"/abc/", "/abc/"},

            {"/abc/path/longer", "/abc/path/longer"},
            {"/abc/path/longer/", "/abc/path/longer/"},

            {"/abc/*", "/abc/hello.jsp"},
            {"/abc/*", "/abc/a"},
            {"/abc/*", "/abc/a/hello.jsp"},
            {"/abc/*", "/abc/a/b"},
            {"/abc/*", "/abc/a/b/hello.jsp"},
            {"/abc/*", "/abc/a/b/c"},
            {"/abc/*", "/abc/a/b/c/hello.jsp"},

            {"/abc/def/*", "/abc/def/gf"},
            {"/abc/def/*", "/abc/def/gf.html"},
            {"/abc/def/*", "/abc/def/ghi"},
            {"/abc/def/*", "/abc/def/ghi/"},
            {"/abc/def/*", "/abc/def/ghi/hello.html"},

            {"*.do", "/abc.do"},
            {"*.do", "/abc/hello.do"},
            {"*.do", "/abc/def/hello.do"},
            {"*.do", "/abc/def/ghi/hello.do"},

            {"*.jsp", "/abc.jsp"},
            {"*.jsp", "/abc/hello.jsp"},
            {"*.jsp", "/abc/def/hello.jsp"},
            {"*.jsp", "/abc/def/ghi/hello.jsp"},

            {"/", "/Other"},
            {"/", "/Other/hello.do"},
            {"/", "/Other/path"},
            {"/", "/Other/path/hello.do"},
            {"/", "/abc/def"},

            {"/abc:/def", "/abc:/def"}
        };

        for (String[] matchCase : matchCases)
        {
            assertMatch(true, matchCase);
        }
    }

    @Test
    public void testFalseMatch() throws IOException
    {
        String[][] matchCases = {

            {"/abc", "/abcd"},
            {"/abc/", "/abcd/"},

            {"/abc/path/longer", "/abc/path/longer/"},
            {"/abc/path/longer", "/abc/path/longer1"},
            {"/abc/path/longer/", "/abc/path/longer"},
            {"/abc/path/longer/", "/abc/path/longer1/"},

            {"/*.jsp", "/hello.jsp"},
            {"/abc/*.jsp", "/abc/hello.jsp"},

            {"*.jsp", "/hello.1jsp"},
            {"*.jsp", "/hello.jsp1"},
            {"*.jsp", "/hello.do"},

            {"*.jsp", "/abc/hello.do"},
            {"*.jsp", "/abc/def/hello.do"},
            {"*.jsp", "/abc.do"}
        };

        for (String[] matchCase : matchCases)
        {
            assertMatch(false, matchCase);
        }
    }

    private void assertMatch(boolean flag, String[] matchCase) throws IOException
    {
        _rule.setPattern(matchCase[0]);
        final String uri = matchCase[1];

        String result = _rule.matchAndApply(uri,
            new Request(null, null)
            {
                {
                    setMetaData(new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_1_0, HttpFields.EMPTY));
                }
            }, null
        );

        assertEquals(flag, result != null, "pattern: " + matchCase[0] + " uri: " + matchCase[1]);
    }

    private class TestPatternRule extends PatternRule
    {
        @Override
        public String apply(String target,
                            HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            return target;
        }
    }
}
