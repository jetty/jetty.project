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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests of poor various name=value scenarios and expectations of results
 * due to our efforts at being lenient with what we receive.
 */
public class RFC6265CookieParserLenientTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            // Simple test to verify behavior
            Arguments.of("x=y", "x", "y"),
            Arguments.of("key=value", "key", "value"),

            // Tests that conform to RFC2109
            // RFC2109 - token values
            //   token          = 1*<any CHAR except CTLs or tspecials>
            //   CHAR           = <any US-ASCII character (octets 0 - 127)>
            //   CTL            = <any US-ASCII control character
            //                    (octets 0 - 31) and DEL (127)>
            //   SP             = <US-ASCII SP, space (32)>
            //   HT             = <US-ASCII HT, horizontal-tab (9)>
            //   tspecials      = "(" | ")" | "<" | ">" | "@"
            //                  | "," | ";" | ":" | "\" | <">
            //                  | "/" | "[" | "]" | "?" | "="
            //                  | "{" | "}" | SP | HT
            Arguments.of("abc=xyz", "abc", "xyz"),
            Arguments.of("abc=!bar", "abc", "!bar"),
            Arguments.of("abc=#hash", "abc", "#hash"),
            Arguments.of("abc=#hash", "abc", "#hash"),
            // RFC2109 - quoted-string values
            //   quoted-string  = ( <"> *(qdtext) <"> )
            //   qdtext         = <any TEXT except <">>

            // rejected, as name cannot be DQUOTED
            Arguments.of("\"a\"=bcd", null, null),
            Arguments.of("\"a\"=\"b c d\"", null, null),

            // lenient with spaces and EOF
            Arguments.of("abc=", "abc", ""),
            Arguments.of("abc= ", "abc", ""),
            Arguments.of("abc= x", "abc", "x"),
            Arguments.of("abc = ", "abc", ""),
            Arguments.of("abc = ;", "abc", ""),
            Arguments.of("abc = ; ", "abc", ""),
            Arguments.of("abc = x ", "abc", "x"),
            Arguments.of("abc=\"\"", "abc", ""),
            Arguments.of("abc= \"\" ", "abc", ""),
            Arguments.of("abc= \"x\" ", "abc", "x"),
            Arguments.of("abc= \"x\" ;", "abc", "x"),
            Arguments.of("abc= \"x\" ; ", "abc", "x"),

            // The backslash character ("\") may be used as a single-character quoting
            // mechanism only within quoted-string and comment constructs.
            //   quoted-pair    = "\" CHAR
            Arguments.of("abc=\"xyz\"", "abc", "xyz"),
            Arguments.of("abc=\"!bar\"", "abc", "!bar"),
            Arguments.of("abc=\"#hash\"", "abc", "#hash"),
            // RFC2109 - other valid name types that conform to 'attr'/'token' syntax
            Arguments.of("!f!o!o!=wat", "!f!o!o!", "wat"),
            Arguments.of("__MyHost=Foo", "__MyHost", "Foo"),
            Arguments.of("some-thing-else=to-parse", "some-thing-else", "to-parse"),
            Arguments.of("$foo=bar", "$foo", "bar"),

            // Tests that conform to RFC6265
            Arguments.of("abc=foobar!", "abc", "foobar!"),
            Arguments.of("abc=\"foobar!\"", "abc", "foobar!")

        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLenientBehavior(String rawHeader, String expectedName, String expectedValue)
    {
        TestCutter cutter = new TestCutter();
        cutter.parseField(rawHeader);

        if (expectedName == null)
            assertThat("Cookies.length", cutter.names.size(), is(0));
        else
        {
            assertThat("Cookies.length", cutter.names.size(), is(1));
            assertThat("Cookie.name", cutter.names.get(0), is(expectedName));
            assertThat("Cookie.value", cutter.values.get(0), is(expectedValue));
        }
    }

    static class TestCutter implements CookieParser.Handler
    {
        CookieParser parser;
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();

        protected TestCutter()
        {
            parser = new RFC6265CookieParser(this, CookieCompliance.RFC6265, null);
        }

        public void parseField(String field)
        {
            parser.parseField(field);
        }

        @Override
        public void addCookie(String cookieName, String cookieValue, int cookieVersion, String cookieDomain, String cookiePath, String cookieComment)
        {
            names.add(cookieName);
            values.add(cookieValue);
        }
    }
}
