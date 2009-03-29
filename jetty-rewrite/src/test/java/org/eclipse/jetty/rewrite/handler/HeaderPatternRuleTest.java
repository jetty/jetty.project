// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.Enumeration;


public class HeaderPatternRuleTest extends AbstractRuleTestCase
{
    private HeaderPatternRule _rule;
    
    public void setUp() throws Exception
    {
        super.setUp();

        _rule = new HeaderPatternRule();
        _rule.setPattern("*");
    }

    public void testHeaderWithTextValues() throws IOException
    {
        // different keys
        String headers[][] = { 
                { "hnum#1", "test1" }, 
                { "hnum#2", "2test2" },
                { "hnum#3", "test3" } 
        };

        assertHeaders(headers);
    }

    public void testHeaderWithNumberValues() throws IOException
    {
        String headers[][] = { 
                { "hello", "1" }, 
                { "hello", "-1" },
                { "hello", "100" },
                { "hello", "100" },
                { "hello", "100" },
                { "hello", "100" },
                { "hello", "100" },
                
                { "hello1", "200" }
        };

        assertHeaders(headers);
    }
    
    public void testHeaderOverwriteValues() throws IOException
    {
        String headers[][] = {
                { "size", "100" },
                { "size", "200" },
                { "size", "300" },
                { "size", "400" },
                { "size", "500" },
                { "title", "abc" },
                { "title", "bac" },
                { "title", "cba" },
                { "title1", "abba" },
                { "title1", "abba1" },
                { "title1", "abba" },
                { "title1", "abba1" }
        };
        
        assertHeaders(headers);
        
        Enumeration e = _response.getHeaders("size");
        int count = 0;
        while (e.hasMoreElements())
        {
            e.nextElement();
            count++;
        }
        
        assertEquals(1, count);
        assertEquals("500", _response.getHeader("size"));
        assertEquals("cba", _response.getHeader("title"));
        assertEquals("abba1", _response.getHeader("title1"));
    }

    private void assertHeaders(String headers[][]) throws IOException
    {
        for (int i = 0; i < headers.length; i++)
        {
            _rule.setName(headers[i][0]);
            _rule.setValue(headers[i][1]);

            _rule.apply(null, _request, _response);

            assertEquals(headers[i][1], _response.getHeader(headers[i][0]));
        }
    }
}
