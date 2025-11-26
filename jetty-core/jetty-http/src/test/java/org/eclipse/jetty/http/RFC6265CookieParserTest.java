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
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.http.CookieCompliance.Violation.ATTRIBUTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.ATTRIBUTE_VALUES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_SEPARATOR;
import static org.eclipse.jetty.http.CookieCompliance.Violation.STRIPPED_QUOTES;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RFC6265CookieParserTest
{
    @Test
    public void testRFC2965ModeParseObsoleteSingleCookie()
    {
        // Example field from RFC2109 and RFC2965
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC2965;
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path"
        };
        assertHasViolations(parser, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
    }

    @Test
    public void testRFC6265ModeParseObsoleteSingleCookie()
    {
        // Example field from RFC2109 and RFC2965
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertFalse(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        // Normal cookies, only stripped quote violations.
        assertThat(parser.violations.size(), is(3));

        assertThat("Cookies.length", cookies.size(), is(3));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Path", "/acme", 0, null);
    }

    @Test
    public void testRFC6265StrictModeParseObsoleteSingleCookie()
    {
        // Example field from RFC2109 and RFC2965
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265_STRICT;
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertFalse(cookieCompliance.allows(ATTRIBUTE_VALUES));
        assertFalse(cookieCompliance.allows(STRIPPED_QUOTES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES(forbidden): \"1\"",
            "STRIPPED_QUOTES(forbidden): \"WILE_E_COYOTE\"",
            "STRIPPED_QUOTES(forbidden): \"/acme\""
        };
        assertHasViolations(parser, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(3));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "\"1\"", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Customer", "\"WILE_E_COYOTE\"", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Path", "\"/acme\"", 0, null);
    }

    @Test
    public void testRFC6265ModeWithAttributesParseObsoleteSingleCookie()
    {
        // Example field from RFC2109 and RFC2965
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTES");
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertFalse(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path"
        };
        assertHasViolations(parser, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 0, null);
    }

    @Test
    public void testRFC6265ModeWithAttributesAndAttributeValuesParseObsoleteSingleCookie()
    {
        // Example field from RFC2109 and RFC2965
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTES,ATTRIBUTE_VALUES");
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path"
        };
        assertHasViolations(parser, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
    }

    @Test
    public void testRFC6265ModeWithAttributeValuesParseObsoleteSingleCookie()
    {
        // Example field from RFC2109 and RFC2965
        String rawCookie = "$Version=\"1\"; Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTE_VALUES");
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES(forbidden): $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES(forbidden): $Path"
        };
        assertHasViolations(parser, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
    }

    @Test
    public void testRFC6265ModeWithAttributeValuesParseCookieWithAttributeSyntax()
    {
        // This uses a $NAME style cookie, but with ATTRIBUTE_VALUES enabled, the `$Type` cookie will not be valid.
        String rawCookie = "Customer=\"WILE_E_COYOTE\"; $Type=\"toon\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTE_VALUES");
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser parser = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = parser.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: toon",
            "ATTRIBUTES(forbidden): $Type",
            "INVALID_COOKIES: Invalid Cookie attribute [$Type]"
        };
        assertHasViolations(parser, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 0, null);
    }

    @Test
    public void testRFC2965ModeParseObsoleteDoubleCookie()
    {
        // Example from RFC2965 of two cookies on 1 string.
        // The goal is to parse both the `Customer` and `Part_Number` as separate cookies.
        // In reality, this violates RFC6265 as there should only be 1 cookie (with attributes) per string.
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC2965;
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies.get(1), "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }

    @Test
    public void testRFC6265ModeParseObsoleteDoubleCookie()
    {
        // Example from RFC2965
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertFalse(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme"
            };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(5));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Path", "/acme", 0, null);
        assertCookie("Cookies[3]", cookies.get(3), "Part_Number", "Rocket_Launcher_0001", 0, null);
        assertCookie("Cookies[4]", cookies.get(4), "$Path", "/acme", 0, null);
    }

    @Test
    public void testRFC6265StrictModeParseObsoleteDoubleCookie()
    {
        // Example from RFC2965
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265_STRICT;
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertFalse(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES(forbidden): \"1\"",
            "STRIPPED_QUOTES(forbidden): \"WILE_E_COYOTE\"",
            "STRIPPED_QUOTES(forbidden): \"/acme\"",
            "STRIPPED_QUOTES(forbidden): \"Rocket_Launcher_0001\"",
            "STRIPPED_QUOTES(forbidden): \"/acme\""
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(5));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "\"1\"", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Customer", "\"WILE_E_COYOTE\"", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Path", "\"/acme\"", 0, null);
        assertCookie("Cookies[3]", cookies.get(3), "Part_Number", "\"Rocket_Launcher_0001\"", 0, null);
        assertCookie("Cookies[4]", cookies.get(4), "$Path", "\"/acme\"", 0, null);
    }

    @Test
    public void testRFC6265ModeWithAttributesParseObsoleteDoubleCookie()
    {
        // Example from RFC2965
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTES");
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertFalse(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "Part_Number", "Rocket_Launcher_0001", 0, null);
    }

    @Test
    public void testRFC6265ModeWithAttributeValuesParseObsoleteDoubleCookie()
    {
        // Example from RFC2965
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTE_VALUES");
        assertFalse(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES(forbidden): $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES(forbidden): $Path",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES(forbidden): $Path",
            };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies.get(1), "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }

    @Test
    public void testRFC6265ModeWithAttributesAndAttributeValuesParseObsoleteDoubleCookie()
    {
        // Example from RFC2965
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,ATTRIBUTES,ATTRIBUTE_VALUES");
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies.get(1), "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }

    @Test
    public void testRFC2965ParseObsoleteTripleCookie()
    {
        // Example from RFC2965 of three cookies in 1 string, separated by a `,`
        // This violates RFC6265, which says there should only ever be 1 cookie per String.
        // And the continued use of `$Version="1"` is also a violation.
        String rawCookie = "$Version=\"1\"; " +
            "Customer=\"WILE_E_COYOTE\"; $Path=\"/acme\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"; " +
            "Shipping=\"FedEx\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC2965;
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: WILE_E_COYOTE",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path",
            "STRIPPED_QUOTES: FedEx",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path"
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(3));
        assertCookie("Cookies[0]", cookies.get(0), "Customer", "WILE_E_COYOTE", 1, "/acme");
        assertCookie("Cookies[1]", cookies.get(1), "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
        assertCookie("Cookies[2]", cookies.get(2), "Shipping", "FedEx", 1, "/acme");
    }

    @Test
    public void testRFC2965ParsePathExample()
    {
        String rawCookie = "$Version=\"1\"; " +
            "Part_Number=\"Riding_Rocket_0023\"; $Path=\"/acme/ammo\"; " +
            "Part_Number=\"Rocket_Launcher_0001\"; $Path=\"/acme\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC2965;
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: Riding_Rocket_0023",
            "STRIPPED_QUOTES: /acme/ammo",
            "ATTRIBUTES: $Path",
            "STRIPPED_QUOTES: Rocket_Launcher_0001",
            "STRIPPED_QUOTES: /acme",
            "ATTRIBUTES: $Path"
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "Part_Number", "Riding_Rocket_0023", 1, "/acme/ammo");
        assertCookie("Cookies[1]", cookies.get(1), "Part_Number", "Rocket_Launcher_0001", 1, "/acme");
    }

    @Test
    public void testRFC2109CookieSpoofingExample()
    {
        // These are semicolon separated, making each entry technically an attribute.
        // They should be comma-delimited to be conforming to spec.
        String rawCookie = "$Version=\"1\"; " +
            "session_id=\"1234\"; " +
            "session_id=\"1111\"; $Domain=\".cracker.edu\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC2965;
        assertTrue(cookieCompliance.allows(ATTRIBUTES));
        assertTrue(cookieCompliance.allows(ATTRIBUTE_VALUES));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: 1234",
            "STRIPPED_QUOTES: 1111",
            "STRIPPED_QUOTES: .cracker.edu",
            "ATTRIBUTES: $Domain"
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "session_id", "1234", 1, null);
        assertCookie("Cookies[1]", cookies.get(1), "session_id", "1111", 1, null);
    }

    @Test
    public void testRFC2965ParseCookieSpoofingExample()
    {
        // Example from RFC2965 of two cookies in 1 string, separated by a `,`
        // This violates RFC6265, which says there should only ever be 1 cookie per String.
        // And the continued use of `$Version="1"` is also a violation.
        String rawCookie = "$Version=\"1\"; session_id=\"1234\", " +
            "$Version=\"1\"; session_id=\"1111\"; $Domain=\".cracker.edu\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC2965;
        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: 1234",
            "COMMA_SEPARATOR: " + rawCookie,
            "STRIPPED_QUOTES: 1",
            "ATTRIBUTES: $Version",
            "STRIPPED_QUOTES: 1111",
            "STRIPPED_QUOTES: .cracker.edu",
            "ATTRIBUTES: $Domain"
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "session_id", "1234", 1, null);
        assertCookie("Cookies[1]", cookies.get(1), "session_id", "1111", 1, null);
    }

    @Test
    public void testRFC6265ParseCookieSpoofingExample()
    {
        String rawCookie = "$Version=\"1\"; session_id=\"1234\", " +
            "$Version=\"1\"; session_id=\"1111\"; $Domain=\".cracker.edu\"";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;
        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "STRIPPED_QUOTES: 1234",
            "COMMA_SEPARATOR(forbidden): " + rawCookie,
            "INVALID_COOKIES: Illegal character ',' in " + rawCookie,
            "STRIPPED_QUOTES: 1111",
            "STRIPPED_QUOTES: .cracker.edu",
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(3));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "session_id", "1111", 0, null);
        assertCookie("Cookies[2]", cookies.get(2), "$Domain", ".cracker.edu", 0, null);
    }

    @Test
    public void testRFC6265WithCommaSeparatorParseCookieSpoofingExample()
    {
        String rawCookie = "$Version=\"1\"; session_id=\"1234\", " +
            "$Version=\"1\"; session_id=\"1111\"; $Domain=\".cracker.edu\"";

        CookieCompliance cookieCompliance = CookieCompliance.from("RFC6265,COMMA_SEPARATOR");
        assertTrue(cookieCompliance.allows(COMMA_SEPARATOR));

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: 1",
            "STRIPPED_QUOTES: 1234",
            "COMMA_SEPARATOR: " + rawCookie,
            "STRIPPED_QUOTES: 1",
            "STRIPPED_QUOTES: 1111",
            "STRIPPED_QUOTES: .cracker.edu",
            };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(5));
        assertCookie("Cookies[0]", cookies.get(0), "$Version", "1", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "session_id", "1234", 0, null);
        assertCookie("Cookies[3]", cookies.get(2), "$Version", "1", 0, null);
        assertCookie("Cookies[3]", cookies.get(3), "session_id", "1111", 0, null);
        assertCookie("Cookies[4]", cookies.get(4), "$Domain", ".cracker.edu", 0, null);
    }

    @Test
    public void testRFC6265ParseSidExample()
    {
        String rawCookie = "SID=31d4d96e407aad42";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;
        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        assertThat("No violations present", cutter.violations, empty());

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "SID", "31d4d96e407aad42", 0, null);
    }

    @Test
    public void testRFC6265SidLangExample()
    {
        String rawCookie = "SID=31d4d96e407aad42; lang=en-US";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;
        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        assertThat("No violations present", cutter.violations, empty());

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "SID", "31d4d96e407aad42", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "lang", "en-US", 0, null);
    }

    @Test
    public void testKeyValue()
    {
        String rawCookie = "key=value";

        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;
        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(cookieCompliance);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        assertThat("No violations present", cutter.violations, empty());

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "key", "value", 0, null);
    }

    /**
     * Attribute $name=value, following RFC6265 rules.
     */
    @Test
    public void testDollarName()
    {
        String rawCookie = "$key=value";

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        assertThat("No violations present", cutter.violations, empty());

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "$key", "value", 0, null);
    }

    /**
     * Test that the invalid cookie name "testcookie" is ignored because
     * it is missing the required "=" from the RFC6265 'cookie-pair' ANBF.
     */
    @Test
    public void testMultipleCookies()
    {
        String rawCookie = "testcookie; server.id=abcd; server.detail=cfg";

        // The first cookie "testcookie" should be ignored, per RFC6265, as it's missing the "=" sign.

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "INVALID_COOKIES: testcookie"
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "server.id", "abcd", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "server.detail", "cfg", 0, null);
    }

    @Test
    public void testExcessiveSemicolons()
    {
        char[] excessive = new char[65535];
        Arrays.fill(excessive, ';');
        String rawCookie = "foo=bar; " + new String(excessive) + "; xyz=pdq";

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        assertThat("No violations present", cutter.violations, empty());

        assertThat("Cookies.length", cookies.size(), is(2));
        assertCookie("Cookies[0]", cookies.get(0), "foo", "bar", 0, null);
        assertCookie("Cookies[1]", cookies.get(1), "xyz", "pdq", 0, null);
    }

    @Test
    public void testRFC6265QuotesMaintained()
    {
        String rawCookie = "A=\"quotedValue\"";

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265);
        List<Cookie> cookies = cutter.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "A", "quotedValue", 0, null);

        cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265_QUOTED);
        cookies = cutter.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "A", "\"quotedValue\"", 0, null);

        cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265_STRICT);
        cookies = cutter.parseFields(rawCookie);
        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "A", "\"quotedValue\"", 0, null);
    }

    @Test
    public void testRFC2965QuotedEscape()
    {
        String rawCookie = "A=\"double\\\"quote\"";

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC2965);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "STRIPPED_QUOTES: double\"quote",
            "ESCAPE_IN_QUOTES: A=\"double\\\"quote\""
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "A", "double\"quote", 0, null);
    }

    @Test
    public void testRFC2965QuotedSpecial()
    {
        String rawCookie = "A=\", ;\"";

        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC2965);
        List<Cookie> cookies = cutter.parseFields(rawCookie);

        String[] expectedViolations = {
            "SPECIAL_CHARS_IN_QUOTES: Character [,] is not allowed - " + rawCookie,
            "SPECIAL_CHARS_IN_QUOTES: Character [ ] is not allowed - " + rawCookie,
            "SPECIAL_CHARS_IN_QUOTES: Character [;] is not allowed - " + rawCookie,
            "STRIPPED_QUOTES: , ;",
        };
        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(1));
        assertCookie("Cookies[0]", cookies.get(0), "A", ", ;", 0, null);
    }

    public static Stream<Arguments> exampleCookies()
    {
        final String[] NO_VIOLATIONS = new String[0];

        return Stream.of(
            Arguments.of("A=1; B=2; C=3",
                List.of("A=1", "B=2", "C=3"),
                NO_VIOLATIONS),
            Arguments.of("A=\"1\"; B=2; C=3",
                List.of("A=1", "B=2", "C=3"),
                new String[]{
                    "STRIPPED_QUOTES: 1",
                }),
            Arguments.of("A=1 ; B=2; C=3",
                List.of("A=1", "B=2", "C=3"),
                new String[]{
                    "SPACE_IN_VALUES: A=1 ; B=2; C=3"
                }),
            Arguments.of("A=\"1; B=2\"; C=3",
                List.of("C=3"),
                new String[]{
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [;] is not allowed - A=\"1; B=2\"; C=3",
                    "INVALID_COOKIES: Illegal character ';' in quoted section in A=\"1; B=2\"; C=3",
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [ ] is not allowed - A=\"1; B=2\"; C=3",
                    "SPACE_IN_VALUES: A=\"1; B=2\"; C=3",
                    "STRIPPED_QUOTES: 1; B=2",
                }),
            Arguments.of("A=\"1; B=2; C=3",
                List.of(),
                new String[]{
                    // TODO: Review violations, as this can get quite spammy.
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [;] is not allowed - A=\"1; B=2; C=3",
                    "INVALID_COOKIES: Illegal character ';' in quoted section in A=\"1; B=2; C=3",
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [ ] is not allowed - A=\"1; B=2; C=3",
                    "SPACE_IN_VALUES: A=\"1; B=2; C=3",
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [;] is not allowed - A=\"1; B=2; C=3",
                    "INVALID_COOKIES: Illegal character ';' in quoted section in A=\"1; B=2; C=3",
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [ ] is not allowed - A=\"1; B=2; C=3",
                    "SPACE_IN_VALUES: A=\"1; B=2; C=3",
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [;] is not allowed - A=\"1; B=2; C=3",
                    "INVALID_COOKIES: Illegal character ';' in quoted section in A=\"1; B=2; C=3"
                }),
            Arguments.of("A=\"1 B=2\"; C=3",
                List.of("A=1 B=2", "C=3"),
                new String[]{
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [ ] is not allowed - A=\"1 B=2\"; C=3",
                    "SPACE_IN_VALUES: A=\"1 B=2\"; C=3",
                    "STRIPPED_QUOTES: 1 B=2",
                }),
            Arguments.of("A=\"\"1; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "INVALID_COOKIES: A=\"\"1; B=2; C=3",
                    "STRIPPED_QUOTES: ",
                }),
            Arguments.of("A=\"\" ; B=2; C=3",
                List.of("A=", "B=2", "C=3"),
                new String[]{
                    "OPTIONAL_WHITE_SPACE: A=\"\" ; B=2; C=3",
                    "STRIPPED_QUOTES: ",
                }),
            Arguments.of("A=1\"\"; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "INVALID_COOKIES: Illegal character '\"' in A=1\"\"; B=2; C=3"
                }),
            Arguments.of("A=1\"; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "INVALID_COOKIES: Illegal character '\"' in A=1\"; B=2; C=3"
                }),
            Arguments.of("A=1\"1; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "INVALID_COOKIES: Illegal character '\"' in A=1\"1; B=2; C=3"
                }),
            Arguments.of("A= 1; B=2; C=3",
                List.of("A=1", "B=2", "C=3"),
                new String[]{
                    "OPTIONAL_WHITE_SPACE: A= 1; B=2; C=3"
                }),
            Arguments.of("A=\" 1\"; B=2; C=3",
                List.of("A= 1", "B=2", "C=3"),
                new String[]{
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [ ] is not allowed - A=\" 1\"; B=2; C=3",
                    "SPACE_IN_VALUES: A=\" 1\"; B=2; C=3",
                    "STRIPPED_QUOTES:  1",
                }),
            Arguments.of("A=\"1 \"; B=2; C=3",
                List.of("A=1 ", "B=2", "C=3"),
                new String[]{
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [ ] is not allowed - A=\"1 \"; B=2; C=3",
                    "SPACE_IN_VALUES: A=\"1 \"; B=2; C=3",
                    "STRIPPED_QUOTES: 1 ",
                }),
            Arguments.of("A=1,; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "COMMA_SEPARATOR(forbidden): A=1,; B=2; C=3",
                    "INVALID_COOKIES: Illegal character ',' in A=1,; B=2; C=3"
                }),
            Arguments.of("A=\"1,\"; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [,] is not allowed - A=\"1,\"; B=2; C=3",
                    "COMMA_NOT_VALID_OCTET(forbidden): A=\"1,\"; B=2; C=3",
                    "INVALID_COOKIES: Illegal character ',' in quoted section in A=\"1,\"; B=2; C=3",
                    "STRIPPED_QUOTES: 1,",
                }),
            Arguments.of("A=\\1; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "INVALID_COOKIES: Illegal character '\\' in A=\\1; B=2; C=3"
                }),
            Arguments.of("A=\"\\1\"; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "ESCAPE_IN_QUOTES(forbidden): A=\"\\1\"; B=2; C=3",
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [\\] is not allowed - A=\"\\1\"; B=2; C=3",
                    "INVALID_COOKIES: Illegal character '\\' in quoted section in A=\"\\1\"; B=2; C=3",
                    "STRIPPED_QUOTES: \\1",
                }),
            Arguments.of("A=1\u0007; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "INVALID_COOKIES: Illegal character '\u0007' in A=1\u0007; B=2; C=3" // TODO: why?
                }),
            Arguments.of("A=\"1\u0007\"; B=2; C=3",
                List.of("B=2", "C=3"),
                new String[]{
                    "SPECIAL_CHARS_IN_QUOTES(forbidden): Character [\u0007] is not allowed - A=\"1\u0007\"; B=2; C=3",
                    "INVALID_COOKIES: Illegal character '\u0007' in quoted section in A=\"1\u0007\"; B=2; C=3",
                    "STRIPPED_QUOTES: 1\u0007",
                }),
            Arguments.of("€",
                List.of(),
                new String[]{
                    "INVALID_COOKIES: €"
                }),
            Arguments.of("@={}",
                List.of(),
                new String[]{
                    "INVALID_COOKIES: @={}" // TODO: why?
                }),
            Arguments.of("$X=Y; N=V",
                List.of("$X=Y", "N=V"),
                NO_VIOLATIONS),
            Arguments.of("N=V; $X=Y",
                List.of("N=V", "$X=Y"),
                NO_VIOLATIONS)
        );
    }

    @ParameterizedTest
    @MethodSource("exampleCookies")
    public void testRFC6265Cookie(String input, List<String> expectedCookies, String[] expectedViolations)
    {
        TestRFC6265CookieParser cutter = new TestRFC6265CookieParser(CookieCompliance.RFC6265);
        List<Cookie> cookies = cutter.parseFields(input);

        assertHasViolations(cutter, expectedViolations);

        assertThat("Cookies.length", cookies.size(), is(expectedCookies.size()));
        for (int i = 0; i < cookies.size(); i++)
        {
            Cookie cookie = cookies.get(i);
            assertThat(cookie.getName() + "=" + cookie.getValue(), is(expectedCookies.get(i)));
        }
    }

    private void assertHasViolations(TestRFC6265CookieParser cutter, String[] expectedViolations)
    {
        List<String> actualViolations = cutter.violations.stream()
            .map(v -> String.format("%s%s: %s", v.violation().getName(), (!v.allowed() ? "(forbidden)" : ""), v.details()))
            .toList();
        assertThat("Actual Violations " + actualViolations.stream()
                .map(Objects::toString)
                .collect(Collectors.joining(",  \n", "[\n  ", "\n]")),
            actualViolations, containsInAnyOrder(expectedViolations));
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

        @Override
        public String toString()
        {
            return String.format("Cookie[%s=%s,path=%s,version=%d,domain=%s,comment=%s]",
                getName(), getValue(), getPath(), getVersion(), getDomain(), getComment()
            );
        }
    }

    private static class TestRFC6265CookieParser implements ComplianceViolation.Listener, CookieParser.Handler
    {
        private final CookieParser parser;
        private final List<Cookie> cookies = new ArrayList<>();
        private final List<ComplianceViolation.Event> violations = new ArrayList<>();

        private TestRFC6265CookieParser(CookieCompliance compliance)
        {
            parser = new RFC6265CookieParser(this, compliance, this);
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            violations.add(event);
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
}
