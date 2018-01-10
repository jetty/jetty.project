//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class CookieCutterTest
{
    private Cookie[] parseCookieHeaders(CookieCompliance compliance,String... headers)
    {
        CookieCutter cutter = new CookieCutter(compliance);
        for (String header : headers)
        {
            cutter.addCookieField(header);
        }
        return cutter.getCookies();
    }
    
    private void assertCookie(String prefix, Cookie cookie,
                              String expectedName,
                              String expectedValue,
                              int expectedVersion,
                              String expectedPath)
    {
        assertThat(prefix + ".name", cookie.getName(), is(expectedName));
        assertThat(prefix + ".value", cookie.getValue(), is(expectedValue));
        assertThat(prefix + ".version", cookie.getVersion(), is(expectedVersion));
        assertThat(prefix + ".path", cookie.getPath(), is(expectedPath));
    }
    
    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFC_Single()
    {
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC2965,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
    }
    
    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFC_Double()
    {
        String rawCookie = "$Version=\"1\"; " +
                "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
                "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC2965,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }
    
    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFC_Triple()
    {
        String rawCookie = "$Version=\"1\"; " +
                "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
                "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"; " +
                "Shipping=\"FedEx\"; $Path=\"/acme\"";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC2965,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(3));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
        assertCookie("Cookies[2]", cookies[2], "Shipping", "FedEx", 1, "/acme");
    }
    
    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFC_PathExample()
    {
        String rawCookie = "$Version=\"1\"; " +
                "Part_Number=\"Riding_Rocket_0023\"; $Path=\"/acme/ammo\"; " +
                "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC2965,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "Part_Number", "Riding_Rocket_0023", 1, "/acme/ammo");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }
    
    /**
     * Example from RFC2109
     */
    @Test
    public void testRFC2109_CookieSpoofingExample()
    {
        String rawCookie = "$Version=\"1\"; " +
                "session_id=\"1234\"; " +
                "session_id=\"1111\"; $Domain=\".cracker.edu\"";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC2965,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "session_id", "1234", 1, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1111", 1, null);
    }
    
    /**
     * Example from RFC2965
     */
    @Test
    @Ignore("comma separation no longer supported by new RFC6265")
    public void testRFC2965_CookieSpoofingExample()
    {
        String rawCookie = "$Version=\"1\"; session_id=\"1234\", " +
                "$Version=\"1\"; session_id=\"1111\"; $Domain=\".cracker.edu\"";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC6265,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "session_id", "1234", 1, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1111", 1, null);
    }
    
    /**
     * Example from RFC6265
     */
    @Test
    public void testRFC6265_SidExample()
    {
        String rawCookie = "SID=31d4d96e407aad42";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC6265,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "SID", "31d4d96e407aad42", 0, null);
    }
    
    /**
     * Example from RFC6265
     */
    @Test
    public void testRFC6265_SidLangExample()
    {
        String rawCookie = "SID=31d4d96e407aad42; lang=en-US";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC6265,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "SID", "31d4d96e407aad42", 0, null);
        assertCookie("Cookies[1]", cookies[1], "lang", "en-US", 0, null);
    }
    
    /**
     * Basic name=value, following RFC6265 rules
     */
    @Test
    public void testKeyValue()
    {
        String rawCookie = "key=value";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC6265,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "key", "value", 0, null);
    }
    
    /**
     * Basic name=value, following RFC6265 rules
     */
    @Test
    public void testDollarName()
    {
        String rawCookie = "$key=value";
        
        Cookie cookies[] = parseCookieHeaders(CookieCompliance.RFC6265,rawCookie);
        
        assertThat("Cookies.length", cookies.length, is(0));
    }
}
