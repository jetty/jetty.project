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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RegexRuleTest
{
    private RegexRule _rule;

    @BeforeEach
    public void init()
    {
        _rule = new TestRegexRule();
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
            // regex: *.jsp
            {"/.*.jsp", "/hello.jsp"},
            {"/.*.jsp", "/abc/hello.jsp"},

            // regex: /abc or /def
            {"/abc|/def", "/abc"},
            {"/abc|/def", "/def"},

            // regex: *.do or *.jsp
            {".*\\.do|.*\\.jsp", "/hello.do"},
            {".*\\.do|.*\\.jsp", "/hello.jsp"},
            {".*\\.do|.*\\.jsp", "/abc/hello.do"},
            {".*\\.do|.*\\.jsp", "/abc/hello.jsp"},

            {"/abc/.*.htm|/def/.*.htm", "/abc/hello.htm"},
            {"/abc/.*.htm|/def/.*.htm", "/abc/def/hello.htm"},

            // regex: /abc/*.jsp
            {"/abc/.*.jsp", "/abc/hello.jsp"},
            {"/abc/.*.jsp", "/abc/def/hello.jsp"}
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
            {"/abc/.*.jsp", "/hello.jsp"}
        };

        for (String[] matchCase : matchCases)
        {
            assertMatch(false, matchCase);
        }
    }

    private void assertMatch(boolean flag, String[] matchCase) throws IOException
    {
        _rule.setRegex(matchCase[0]);
        final String uri = matchCase[1];
        String result = _rule.matchAndApply(uri,
            new Request(null, null)
            {
                @Override
                public String getRequestURI()
                {
                    return uri;
                }
            }, null
        );

        assertEquals(flag, result != null, "regex: " + matchCase[0] + " uri: " + matchCase[1]);
    }

    private class TestRegexRule extends RegexRule
    {
        @Override
        public String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
        {
            return target;
        }
    }
}
