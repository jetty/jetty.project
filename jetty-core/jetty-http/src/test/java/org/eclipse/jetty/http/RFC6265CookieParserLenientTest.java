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
import static org.hamcrest.Matchers.equalTo;
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
            Arguments.of("x=y", new String[]{"x", "y"}),
            Arguments.of("key=value", new String[]{"key", "value"}),

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
            Arguments.of("abc=xyz", new String[]{"abc", "xyz"}),
            Arguments.of("abc=!bar", new String[]{"abc", "!bar"}),
            Arguments.of("abc=#hash", new String[]{"abc", "#hash"}),
            Arguments.of("abc=#hash", new String[]{"abc", "#hash"}),
            // RFC2109 - quoted-string values
            //   quoted-string  = ( <"> *(qdtext) <"> )
            //   qdtext         = <any TEXT except <">>

            // rejected, as name cannot be DQUOTED
            Arguments.of("\"a\"=bcd", new String[0]),
            Arguments.of("\"a\"=\"b c d\"", new String[0]),

            // lenient with spaces and EOF
            Arguments.of("abc=", new String[]{"abc", ""}),
            Arguments.of("abc= ", new String[]{"abc", ""}),
            Arguments.of("abc= x", new String[]{"abc", "x"}),
            Arguments.of("abc = ", new String[]{"abc", ""}),
            Arguments.of("abc = ;", new String[]{"abc", ""}),
            Arguments.of("abc = ; ", new String[]{"abc", ""}),
            Arguments.of("abc = x ", new String[]{"abc", "x"}),
            Arguments.of("abc=\"\"", new String[]{"abc", ""}),
            Arguments.of("abc= \"\" ", new String[]{"abc", ""}),
            Arguments.of("abc= \"x\" ", new String[]{"abc", "x"}),
            Arguments.of("abc= \"x\" ;", new String[]{"abc", "x"}),
            Arguments.of("abc= \"x\" ; ", new String[]{"abc", "x"}),
            Arguments.of("abc = x y z ", new String[]{"abc", "x y z"}),
            Arguments.of("abc = x  y  z ", new String[]{"abc", "x  y  z"}),
            Arguments.of("abc=a:x b:y c:z", new String[]{"abc", "a:x b:y c:z"}),
            Arguments.of("abc=a;x b;y c;z", new String[]{"abc", "a"}),
            Arguments.of("abc=a  ;b=x", new String[]{"abc", "a", "b", "x"}),

            Arguments.of("abc=x y;def=w z", new String[]{"abc", "x y", "def", "w z"}),
            Arguments.of("abc=\"x y\";def=w z", new String[]{"abc", "x y", "def", "w z"}),
            Arguments.of("abc=x y;def=\"w z\"", new String[]{"abc", "x y", "def", "w z"}),

            // The backslash character ("\") may be used as a single-character quoting
            // mechanism only within quoted-string and comment constructs.
            //   quoted-pair    = "\" CHAR
            Arguments.of("abc=\"xyz\"", new String[]{"abc", "xyz"}),
            Arguments.of("abc=\"!bar\"", new String[]{"abc", "!bar"}),
            Arguments.of("abc=\"#hash\"", new String[]{"abc", "#hash"}),
            // RFC2109 - other valid name types that conform to 'attr'/'token' syntax
            Arguments.of("!f!o!o!=wat", new String[]{"!f!o!o!", "wat"}),
            Arguments.of("__MyHost=Foo", new String[]{"__MyHost", "Foo"}),
            Arguments.of("some-thing-else=to-parse", new String[]{"some-thing-else", "to-parse"}),
            Arguments.of("$foo=bar", new String[]{"$foo", "bar"}),

            // Tests that conform to RFC6265
            Arguments.of("abc=foobar!", new String[]{"abc", "foobar!"}),
            Arguments.of("abc=\"foobar!\"", new String[]{"abc", "foobar!"})
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLenientBehavior(String rawHeader, String... expected)
    {
        TestParser parser = new TestParser();
        parser.parseField(rawHeader);

        assertThat(expected.length % 2, is(0));
        assertThat(parser.names.size(), equalTo(expected.length / 2));
        for (int i = 0; i < expected.length; i += 2)
        {
            int cookie = i / 2;
            assertThat("Cookie.name " + cookie, parser.names.get(cookie), is(expected[i]));
            assertThat("Cookie.value " + cookie, parser.values.get(cookie), is(expected[i + 1]));
        }
    }

    static class TestParser implements CookieParser.Handler
    {
        CookieParser parser;
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();

        protected TestParser()
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
