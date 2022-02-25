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

package org.eclipse.jetty.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CookieCutterTest
{
    private Cookie[] parseCookieHeaders(CookieCompliance compliance, String... headers)
    {
        TestCutter cutter = new TestCutter(compliance, null);
        for (String header : headers)
        {
            cutter.parseFields(header);
        }
        return cutter.cookies.toArray(new Cookie[cutter.cookies.size()]);
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
    public void testRFCSingle()
    {
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
    }

    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFCDouble()
    {
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }

    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFCTriple()
    {
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"; " +
            "Shipping=\"FedEx\"; $Path=\"/acme\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(3));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
        assertCookie("Cookies[2]", cookies[2], "Shipping", "FedEx", 1, "/acme");
    }

    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFCPathExample()
    {
        String rawCookie = "$Version=\"1\"; " +
            "Part_Number=\"Riding_Rocket_0023\"; $Path=\"/acme/ammo\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "Part_Number", "Riding_Rocket_0023", 1, "/acme/ammo");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }

    /**
     * Example from RFC2109
     */
    @Test
    public void testRFC2109CookieSpoofingExample()
    {
        String rawCookie = "$Version=\"1\"; " +
            "session_id=\"1234\"; " +
            "session_id=\"1111\"; $Domain=\".cracker.edu\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "session_id", "1234", 1, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1111", 1, null);
    }

    /**
     * Example from RFC2965
     */
    @Test
    public void testRFC2965CookieSpoofingExample()
    {
        String rawCookie = "$Version=\"1\"; session_id=\"1234\", " +
            "$Version=\"1\"; session_id=\"1111\"; $Domain=\".cracker.edu\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "session_id", "1234", 1, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1111", 1, null);

        cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "session_id", "1234\", $Version=\"1", 0, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1111", 0, null);
    }

    /**
     * Example from RFC6265
     */
    @Test
    public void testRFC6265SidExample()
    {
        String rawCookie = "SID=31d4d96e407aad42";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "SID", "31d4d96e407aad42", 0, null);
    }

    /**
     * Example from RFC6265
     */
    @Test
    public void testRFC6265SidLangExample()
    {
        String rawCookie = "SID=31d4d96e407aad42; lang=en-US";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

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

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

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

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

        assertThat("Cookies.length", cookies.length, is(0));
    }

    @Test
    public void testMultipleCookies()
    {
        String rawCookie = "testcookie; server.id=abcd; server.detail=cfg";

        // The first cookie "testcookie" should be ignored, per RFC6265, as it's missing the "=" sign.

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "server.id", "abcd", 0, null);
        assertCookie("Cookies[1]", cookies[1], "server.detail", "cfg", 0, null);
    }

    @Test
    public void testExcessiveSemicolons()
    {
        char[] excessive = new char[65535];
        Arrays.fill(excessive, ';');
        String rawCookie = "foo=bar; " + excessive + "; xyz=pdq";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "foo", "bar", 0, null);
        assertCookie("Cookies[1]", cookies[1], "xyz", "pdq", 0, null);
    }

    static class Cookie
    {
        String name;
        String value;
        String domain;
        String path;
        int version;
        String comment;

        public Cookie(String name, String value, String domain, String path, int version, String comment)
        {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.version = version;
            this.comment = comment;
        }

        public String getName()
        {
            return name;
        }

        public String getValue()
        {
            return value;
        }

        public String getDomain()
        {
            return domain;
        }

        public String getPath()
        {
            return path;
        }

        public int getVersion()
        {
            return version;
        }

        public String getComment()
        {
            return comment;
        }
    }

    class TestCutter extends CookieCutter
    {
        List<Cookie> cookies = new ArrayList<>();

        public TestCutter(CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
        {
            super(compliance, complianceListener);
        }

        @Override
        protected void addCookie(String cookieName, String cookieValue, String cookieDomain, String cookiePath, int cookieVersion, String cookieComment)
        {
            cookies.add(new Cookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment));
        }

        public void parseFields(String... fields)
        {
            super.parseFields(Arrays.asList(fields));
        }
    }
}
