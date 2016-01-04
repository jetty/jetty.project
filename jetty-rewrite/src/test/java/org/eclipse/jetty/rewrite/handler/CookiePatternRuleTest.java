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

package org.eclipse.jetty.rewrite.handler;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Enumeration;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Before;
import org.junit.Test;

public class CookiePatternRuleTest extends AbstractRuleTestCase
{
    @Before
    public void init() throws Exception
    {
        start(false);
    }

    @Test
    public void testSingleCookie() throws IOException
    {
        String[] cookie = {"cookie", "value"};
        assertCookies(cookie,true);
    }
    
    @Test
    public void testSetAlready() throws IOException
    {
        String[] cookie = {"set", "already"};
        assertCookies(cookie,false);
    }

    private void assertCookies(String[] cookie,boolean setExpected) throws IOException
    {
            // set cookie pattern
            CookiePatternRule rule = new CookiePatternRule();
            rule.setPattern("*");
            rule.setName(cookie[0]);
            rule.setValue(cookie[1]);

            // System.out.println(rule.toString());

            // apply cookie pattern
            rule.apply(_request.getRequestURI(), _request, _response);

            // verify
            HttpFields httpFields = _response.getHttpFields();
            Enumeration<String> e = httpFields.getValues(HttpHeader.SET_COOKIE.asString());
            boolean set = false;
            while (e.hasMoreElements())
            {
                String[] result = (e.nextElement()).split("=");
                assertEquals(cookie[0], result[0]);
                assertEquals(cookie[1], result[1]);
                set=true;
            }
            
            assertEquals(setExpected,set);
    }
}
