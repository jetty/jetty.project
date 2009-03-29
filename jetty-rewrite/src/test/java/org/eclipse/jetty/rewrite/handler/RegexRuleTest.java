// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Request;

public class RegexRuleTest extends TestCase
{
    private RegexRule _rule;
    
    public void setUp()
    {
        _rule = new TestRegexRule();
        
    }
    
    public void tearDown()
    {
        _rule = null;
    }
    
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
        
        for (int i = 0; i < matchCases.length; i++)
        {
            String[] matchCase = matchCases[i];
            assertMatch(true, matchCase);
        }
    }
    
    public void testFalseMatch() throws IOException
    {
        String[][] matchCases = {
                {"/abc/.*.jsp", "/hello.jsp"}
        };
        
        for (int i = 0; i < matchCases.length; i++)
        {
            String[] matchCase = matchCases[i];
            assertMatch(false, matchCase);
        }
    }
    
    private void assertMatch(boolean flag, String[] matchCase) throws IOException
    {
        _rule.setRegex(matchCase[0]);
        final String uri=matchCase[1];
        String result = _rule.matchAndApply(uri,
        new Request()
        {
            public String getRequestURI()
            {
                return uri;
            }
        }, null
        );
        
        assertEquals("regex: " + matchCase[0] + " uri: " + matchCase[1], flag, result!=null);
    }
    
    private class TestRegexRule extends RegexRule
    {
        public String apply(String target,HttpServletRequest request,HttpServletResponse response, Matcher matcher) throws IOException
        {
            return target;
        }
    }
}
