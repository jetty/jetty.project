//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.stream.Stream;
import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.CookieCompliance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests of poor various name=value scenarios and expectations of results
 * due to our efforts at being lenient with what we receive.
 */
public class CookieCutterLenientTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            // Simple test to verify behavior
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
            Arguments.of("abc = ", "abc", ""),
            Arguments.of("abc = ;", "abc", ""),
            Arguments.of("abc = ; ", "abc", ""),
            Arguments.of("abc = x ", "abc", "x"),
            Arguments.of("abc = e f g ", "abc", "e f g"),
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
            // RFC2109 - names with attr/token syntax starting with '$' (and not a cookie reserved word)
            // See https://tools.ietf.org/html/draft-ietf-httpbis-cookie-prefixes-00#section-5.2
            // Cannot pass names through as javax.servlet.http.Cookie class does not allow them
            Arguments.of("$foo=bar", null, null),

            // Tests that conform to RFC6265
            Arguments.of("abc=foobar!", "abc", "foobar!"),
            Arguments.of("abc=\"foobar!\"", "abc", "foobar!"),

            // Internal quotes
            Arguments.of("foo=bar\"baz", "foo", "bar\"baz"),
            Arguments.of("foo=\"bar\"baz\"", "foo", "bar\"baz"),
            Arguments.of("foo=\"bar\"-\"baz\"", "foo", "bar\"-\"baz"),
            Arguments.of("foo=\"bar'-\"baz\"", "foo", "bar'-\"baz"),
            Arguments.of("foo=\"bar''-\"baz\"", "foo", "bar''-\"baz"),
            // These seem dubious until you realize the "lots of equals signs" below works
            Arguments.of("foo=\"bar\"=\"baz\"", "foo", "bar\"=\"baz"),
            Arguments.of("query=\"?b=c\"&\"d=e\"", "query", "?b=c\"&\"d=e"),
            // Escaped quotes
            Arguments.of("foo=\"bar\\\"=\\\"baz\"", "foo", "bar\"=\"baz"),

            // Unterminated Quotes
            Arguments.of("x=\"abc", "x", "\"abc"),
            // Unterminated Quotes with valid cookie params after it
            Arguments.of("x=\"abc $Path=/", "x", "\"abc $Path=/"),
            // Unterminated Quotes with trailing escape
            Arguments.of("x=\"abc\\", "x", "\"abc\\"),

            // UTF-8 raw values (not encoded) - VIOLATION of RFC6265
            Arguments.of("2sides=\u262F", null, null), // 2 byte (YIN YANG) - rejected due to not being DQUOTED
            Arguments.of("currency=\"\u20AC\"", "currency", "\u20AC"), // 3 byte (EURO SIGN)
            Arguments.of("gothic=\"\uD800\uDF48\"", "gothic", "\uD800\uDF48"), // 4 byte (GOTHIC LETTER HWAIR)

            // Spaces
            Arguments.of("foo=bar baz", "foo", "bar baz"),
            Arguments.of("foo=\"bar baz\"", "foo", "bar baz"),
            Arguments.of("z=a b c d e f g", "z", "a b c d e f g"),

            // Bad tspecials usage - VIOLATION of RFC6265
            Arguments.of("foo=bar;baz", "foo", "bar"),
            Arguments.of("foo=\"bar;baz\"", "foo", "bar;baz"),
            Arguments.of("z=a;b,c:d;e/f[g]", "z", "a"),
            Arguments.of("z=\"a;b,c:d;e/f[g]\"", "z", "a;b,c:d;e/f[g]"),
            Arguments.of("name=quoted=\"\\\"badly\\\"\"", "name", "quoted=\"\\\"badly\\\"\""), // someone attempting to escape a DQUOTE from within a DQUOTED pair)

            // Quoted with other Cookie keywords
            Arguments.of("x=\"$Version=0\"", "x", "$Version=0"),
            Arguments.of("x=\"$Path=/\"", "x", "$Path=/"),
            Arguments.of("x=\"$Path=/ $Domain=.foo.com\"", "x", "$Path=/ $Domain=.foo.com"),
            Arguments.of("x=\" $Path=/ $Domain=.foo.com \"", "x", " $Path=/ $Domain=.foo.com "),
            Arguments.of("a=\"b; $Path=/a; c=d; $PATH=/c; e=f\"; $Path=/e/", "a", "b; $Path=/a; c=d; $PATH=/c; e=f"), // VIOLATES RFC6265

            // Lots of equals signs
            Arguments.of("query=b=c&d=e", "query", "b=c&d=e"),

            // Escaping
            Arguments.of("query=%7B%22sessionCount%22%3A5%2C%22sessionTime%22%3A14151%7D", "query", "%7B%22sessionCount%22%3A5%2C%22sessionTime%22%3A14151%7D"),

            // Google cookies (seen in wild, has `tspecials` of ':' in value)
            Arguments.of("GAPS=1:A1aaaAaAA1aaAAAaa1a11a:aAaaAa-aaA1-", "GAPS", "1:A1aaaAaAA1aaAAAaa1a11a:aAaaAa-aaA1-"),

            // Strong abuse of cookie spec (lots of tspecials) - VIOLATION of RFC6265
            Arguments.of("$Version=0; rToken=F_TOKEN''!--\"</a>=&{()}", "rToken", "F_TOKEN''!--\"</a>=&{()}"),

            // Commas that were not commas
            Arguments.of("name=foo,bar", "name", "foo,bar"),
            Arguments.of("name=foo , bar", "name", "foo , bar"),
            Arguments.of("name=foo , bar, bob", "name", "foo , bar, bob")
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLenientBehavior(String rawHeader, String expectedName, String expectedValue)
    {
        CookieCutter cutter = new CookieCutter(CookieCompliance.RFC6265_LEGACY);
        cutter.addCookieField(rawHeader);
        Cookie[] cookies = cutter.getCookies();
        if (expectedName == null)
            assertThat("Cookies.length", cookies.length, is(0));
        else
        {
            assertThat("Cookies.length", cookies.length, is(1));
            assertThat("Cookie.name", cookies[0].getName(), is(expectedName));
            assertThat("Cookie.value", cookies[0].getValue(), is(expectedValue));
        }
    }
}
