//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RFC6265CookieParserTest
{
    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFC2965Single()
    {
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        // Test with RFC 2965.
        TestCookieParser parser = new TestCookieParser(CookieCompliance.RFC2965);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
        // There are 2 attributes, so 2 violations.
        assertThat(parser.violations.size(), is(2));

        // Same test with RFC6265.
        parser = new TestCookieParser(CookieCompliance.RFC6265);
        cookies = parser.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(3));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Path", "/acme", 0, null);

        // There attributes are seen as just normal cookies, so no violations
        assertThat(parser.violations.size(), is(0));

        // Same again, but allow attributes which are ignored
        parser = new TestCookieParser(CookieCompliance.from("RFC6265,ATTRIBUTES"));
        cookies = parser.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 0, null);

        // There attributes are seen as just normal cookies, so no violations
        assertThat(parser.violations.size(), is(2));

        // Same again, but allow attributes which are not ignored
        parser = new TestCookieParser(CookieCompliance.from("RFC6265,ATTRIBUTE_VALUES"));
        cookies = parser.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");

        // There attributes are seen as just normal cookies, so no violations
        assertThat(parser.violations.size(), is(2));

        // Same test with RFC 6265 strict should throw.
        parser = new TestCookieParser(CookieCompliance.RFC6265_STRICT);
        cookies = parser.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(3));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Path", "/acme", 0, null);

        // There attributes are seen as just normal cookies, so no violations
        assertThat(parser.violations.size(), is(0));
    }

    /**
     * Example from RFC2109 and RFC2965
     */
    @Test
    public void testRFC2965Double()
    {
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 1, "/acme");

        cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);
        assertThat("Cookies.length", cookies.length, is(5));
        assertCookie("Cookies[0]", cookies[0], "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies[1], "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[2]", cookies[2], "$Path", "/acme", 0, null);
        assertCookie("Cookies[3]", cookies[3], "Part_Number", "Rocket_Launcher_0001", 0, null);
        assertCookie("Cookies[4]", cookies[4], "$Path", "/acme", 0, null);

        cookies = parseCookieHeaders(CookieCompliance.from("RFC6265,ATTRIBUTES"), rawCookie);
        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[1]", cookies[1], "Part_Number", "Rocket_Launcher_0001", 0, null);

        cookies = parseCookieHeaders(CookieCompliance.from("RFC6265,ATTRIBUTE_VALUES"), rawCookie);
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
        assertThat("Cookies.length", cookies.length, is(3));
        assertCookie("Cookies[0]", cookies[0], "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1111", 0, null);
        assertCookie("Cookies[2]", cookies[2], "$Domain", ".cracker.edu", 0, null);

        cookies = parseCookieHeaders(CookieCompliance.from("RFC6265,COMMA_SEPARATOR"), rawCookie);
        assertThat("Cookies.length", cookies.length, is(5));
        assertCookie("Cookies[0]", cookies[0], "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies[1], "session_id", "1234", 0, null);
        assertCookie("Cookies[3]", cookies[2], "$Version", "1", 0, null);
        assertCookie("Cookies[3]", cookies[3], "session_id", "1111", 0, null);
        assertCookie("Cookies[4]", cookies[4], "$Domain", ".cracker.edu", 0, null);
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

        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "$key", "value", 0, null);
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
        String rawCookie = "foo=bar; " + new String(excessive) + "; xyz=pdq";

        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, rawCookie);

        assertThat("Cookies.length", cookies.length, is(2));
        assertCookie("Cookies[0]", cookies[0], "foo", "bar", 0, null);
        assertCookie("Cookies[1]", cookies[1], "xyz", "pdq", 0, null);
    }

    @Test
    public void testRFC2965QuotedEscape()
    {
        String rawCookie = "A=\"double\\\"quote\"";
        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "A", "double\"quote", 0, null);
    }

    @Test
    public void testRFC2965QuotedSpecial()
    {
        String rawCookie = "A=\", ;\"";
        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC2965, rawCookie);

        assertThat("Cookies.length", cookies.length, is(1));
        assertCookie("Cookies[0]", cookies[0], "A", ", ;", 0, null);
    }

    public static List<Param> parameters()
    {
        return Arrays.asList(
            new Param("A=1; B=2; C=3", "A=1", "B=2", "C=3"),
            new Param("A=\"1\"; B=2; C=3", "A=1", "B=2", "C=3"),
            new Param("A=1 ; B=2; C=3", "A=1", "B=2", "C=3"),
            new Param("A=\"1; B=2\"; C=3", "C=3"),
            new Param("A=\"1; B=2; C=3"),
            new Param("A=\"1 B=2\"; C=3", "A=1 B=2", "C=3"),
            new Param("A=\"\"1; B=2; C=3", "B=2", "C=3"),
            new Param("A=\"\" ; B=2; C=3", "A=", "B=2", "C=3"),
            new Param("A=1\"\"; B=2; C=3", "B=2", "C=3"),
            new Param("A=1\"; B=2; C=3", "B=2", "C=3"),
            new Param("A=1\"1; B=2; C=3", "B=2", "C=3"),
            new Param("A= 1; B=2; C=3", "A=1", "B=2", "C=3"),
            new Param("A=\" 1\"; B=2; C=3", "A= 1", "B=2", "C=3"),
            new Param("A=\"1 \"; B=2; C=3", "A=1 ", "B=2", "C=3"),
            new Param("A=1,; B=2; C=3", "B=2", "C=3"),
            new Param("A=\"1,\"; B=2; C=3", "B=2", "C=3"),
            new Param("A=\\1; B=2; C=3", "B=2", "C=3"),
            new Param("A=\"\\1\"; B=2; C=3", "B=2", "C=3"),
            new Param("A=1\u0007; B=2; C=3", "B=2", "C=3"),
            new Param("A=\"1\u0007\"; B=2; C=3", "B=2", "C=3"),
            new Param("€"),
            new Param("@={}"),
            new Param("$X=Y; N=V", "$X=Y", "N=V"),
            new Param("N=V; $X=Y", "N=V", "$X=Y")
        );
    }

    @ParameterizedTest
    @MethodSource("parameters")
    public void testRFC6265Cookie(Param param)
    {
        Cookie[] cookies = parseCookieHeaders(CookieCompliance.RFC6265, param.input);

        assertThat("Cookies.length", cookies.length, is(param.expected.size()));
        for (int i = 0; i < cookies.length; i++)
        {
            Cookie cookie = cookies[i];
            assertThat(cookie.getName() + "=" + cookie.getValue(), is(param.expected.get(i)));
        }
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

    private Cookie[] parseCookieHeaders(CookieCompliance compliance, String... headers)
    {
        TestCookieParser cutter = new TestCookieParser(compliance);
        for (String header : headers)
        {
            cutter.parseFields(header);
        }
        return cutter.cookies.toArray(Cookie[]::new);
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

    private static class TestCookieParser implements ComplianceViolation.Listener, CookieParser.Handler
    {
        private final CookieParser parser;
        private final List<Cookie> cookies = new ArrayList<>();
        private final List<CookieCompliance.Violation> violations = new ArrayList<>();

        private TestCookieParser(CookieCompliance compliance)
        {
            parser = new RFC6265CookieParser(this, compliance, this);
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Mode mode, ComplianceViolation violation, String details)
        {
            violations.add((CookieCompliance.Violation)violation);
        }

        private List<Cookie> parseFields(String... fields)
        {
            parser.parseFields(Arrays.asList(fields));
            return cookies;
        }

        @Override
        public void addCookie(String cookieName, String cookieValue, int cookieVersion, String cookieDomain, String cookiePath, String cookieComment)
        {
            cookies.add(new Cookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment));
        }
    }

    private static class Param
    {
        private final String input;
        private final List<String> expected;

        public Param(String input, String... expected)
        {
            this.input = input;
            this.expected = Arrays.asList(expected);
        }

        @Override
        public String toString()
        {
            return input + " -> " + expected.toString();
        }
    }
}
