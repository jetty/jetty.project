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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;


public class CookiePatternRuleTest extends AbstractRuleTestCase
{   
    public void setUp() throws Exception
    {
        super.setUp();
    }
    
    public void testSingleCookie() throws IOException
    {
        String[][] cookie = {
                {"cookie", "value"}
        };

        assertCookies(cookie);
    }
    
    public void testMultipleCookies() throws IOException
    {
        String[][] cookies = {
                {"cookie", "value"},
                {"name", "wolfgangpuck"},
                {"age", "28"}
        };
        
        assertCookies(cookies);
    }
    
    private void assertCookies(String[][] cookies) throws IOException
    {
        for (int i = 0; i < cookies.length; i++)
        {
            String[] cookie = cookies[i];
            
            // set cookie pattern
            CookiePatternRule rule = new CookiePatternRule();
            rule.setPattern("*");
            rule.setName(cookie[0]);
            rule.setValue(cookie[1]);

            System.out.println(rule.toString());

            // apply cookie pattern
            rule.apply(_request.getRequestURI(), _request, _response);
            
            // verify
            HttpFields httpFields = _response.getHttpFields();
            Enumeration e = httpFields.getValues(HttpHeaders.SET_COOKIE_BUFFER);
            int index = 0;
            while (e.hasMoreElements())
            {
                String[] result = ((String)e.nextElement()).split("=");
                assertEquals(cookies[index][0], result[0]);
                assertEquals(cookies[index][1], result[1]);
                
                // +1 cookies index
                index++;
            }
        }
    }
}
