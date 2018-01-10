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

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.Cookie;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Tests of poor various name=value scenarios and expectations of results
 * due to our efforts at being lenient with what we receive.
 */
@RunWith(Parameterized.class)
public class CookieCutter_LenientTest
{
    @Parameterized.Parameters(name = "{0}")
    public static List<String[]> data()
    {
        List<String[]> ret = new ArrayList<>();
        
        // Simple test to verify behavior
        ret.add(new String[]{"key=value", "key", "value"});
        
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
        ret.add(new String[]{"abc=xyz", "abc", "xyz"});
        ret.add(new String[]{"abc=!bar", "abc", "!bar"});
        ret.add(new String[]{"abc=#hash", "abc", "#hash"});
        ret.add(new String[]{"abc=#hash", "abc", "#hash"});
        // RFC2109 - quoted-string values
        //   quoted-string  = ( <"> *(qdtext) <"> )
        //   qdtext         = <any TEXT except <">>

        // lenient with spaces and EOF
        ret.add(new String[]{"abc=", "abc", ""});
        ret.add(new String[]{"abc = ", "abc", ""});
        ret.add(new String[]{"abc = ;", "abc", ""});
        ret.add(new String[]{"abc = ; ", "abc", ""});
        ret.add(new String[]{"abc = x ", "abc", "x"});
        ret.add(new String[]{"abc=\"\"", "abc", ""});
        ret.add(new String[]{"abc= \"\" ", "abc", ""});
        ret.add(new String[]{"abc= \"x\" ", "abc", "x"});
        ret.add(new String[]{"abc= \"x\" ;", "abc", "x"});
        ret.add(new String[]{"abc= \"x\" ; ", "abc", "x"});

        // The backslash character ("\") may be used as a single-character quoting
        // mechanism only within quoted-string and comment constructs.
        //   quoted-pair    = "\" CHAR
        ret.add(new String[]{"abc=\"xyz\"", "abc", "xyz"});
        ret.add(new String[]{"abc=\"!bar\"", "abc", "!bar"});
        ret.add(new String[]{"abc=\"#hash\"", "abc", "#hash"});
        // RFC2109 - other valid name types that conform to 'attr'/'token' syntax
        ret.add(new String[]{"!f!o!o!=wat", "!f!o!o!", "wat"});
        ret.add(new String[]{"__MyHost=Foo", "__MyHost", "Foo"});
        ret.add(new String[]{"some-thing-else=to-parse", "some-thing-else", "to-parse"});
        // RFC2109 - names with attr/token syntax starting with '$' (and not a cookie reserved word)
        // See https://tools.ietf.org/html/draft-ietf-httpbis-cookie-prefixes-00#section-5.2
        // Cannot pass names through as javax.servlet.http.Cookie class does not allow them
        ret.add(new String[]{"$foo=bar", null, null});

        // Tests that conform to RFC6265
        ret.add(new String[]{"abc=foobar!", "abc", "foobar!"});
        ret.add(new String[]{"abc=\"foobar!\"", "abc", "foobar!"});
    
        // Internal quotes
        ret.add(new String[]{"foo=bar\"baz", "foo", "bar\"baz"});
        ret.add(new String[]{"foo=\"bar\"baz\"", "foo", "bar\"baz"});
        ret.add(new String[]{"foo=\"bar\"-\"baz\"", "foo", "bar\"-\"baz"});
        ret.add(new String[]{"foo=\"bar'-\"baz\"", "foo", "bar'-\"baz"});
        ret.add(new String[]{"foo=\"bar''-\"baz\"", "foo", "bar''-\"baz"});
        // These seem dubious until you realize the "lots of equals signs" below works
        ret.add(new String[]{"foo=\"bar\"=\"baz\"", "foo", "bar\"=\"baz"});
        ret.add(new String[]{"query=\"?b=c\"&\"d=e\"", "query", "?b=c\"&\"d=e"});
        // Escaped quotes
        ret.add(new String[]{"foo=\"bar\\\"=\\\"baz\"", "foo", "bar\"=\"baz"});
        
        // Unterminated Quotes
        ret.add(new String[]{"x=\"abc", "x", "\"abc"});
        // Unterminated Quotes with valid cookie params after it
        ret.add(new String[]{"x=\"abc $Path=/", "x", "\"abc $Path=/"});
        // Unterminated Quotes with trailing escape
        ret.add(new String[]{"x=\"abc\\", "x", "\"abc\\"});
    
        // UTF-8 values
        ret.add(new String[]{"2sides=\u262F", "2sides", "\u262f"}); // 2 byte
        ret.add(new String[]{"currency=\"\u20AC\"", "currency", "\u20AC"}); // 3 byte
        ret.add(new String[]{"gothic=\"\uD800\uDF48\"", "gothic", "\uD800\uDF48"}); // 4 byte
        
        // Spaces
        ret.add(new String[]{"foo=bar baz", "foo", "bar baz"});
        ret.add(new String[]{"foo=\"bar baz\"", "foo", "bar baz"});
        ret.add(new String[]{"z=a b c d e f g", "z", "a b c d e f g"});
    
        // Bad tspecials usage
        ret.add(new String[]{"foo=bar;baz", "foo", "bar"});
        ret.add(new String[]{"foo=\"bar;baz\"", "foo", "bar;baz"});
        ret.add(new String[]{"z=a;b,c:d;e/f[g]", "z", "a"});
        ret.add(new String[]{"z=\"a;b,c:d;e/f[g]\"", "z", "a;b,c:d;e/f[g]"});
        
        // Quoted with other Cookie keywords
        ret.add(new String[]{"x=\"$Version=0\"", "x", "$Version=0"});
        ret.add(new String[]{"x=\"$Path=/\"", "x", "$Path=/"});
        ret.add(new String[]{"x=\"$Path=/ $Domain=.foo.com\"", "x", "$Path=/ $Domain=.foo.com"});
        ret.add(new String[]{"x=\" $Path=/ $Domain=.foo.com \"", "x", " $Path=/ $Domain=.foo.com "});
        ret.add(new String[]{"a=\"b; $Path=/a; c=d; $PATH=/c; e=f\"; $Path=/e/", "a", "b; $Path=/a; c=d; $PATH=/c; e=f"});
        
        // Lots of equals signs
        ret.add(new String[]{"query=b=c&d=e", "query", "b=c&d=e"});
    
        // Escaping
        ret.add(new String[]{"query=%7B%22sessionCount%22%3A5%2C%22sessionTime%22%3A14151%7D", "query", "%7B%22sessionCount%22%3A5%2C%22sessionTime%22%3A14151%7D"});
        
        // Google cookies (seen in wild, has `tspecials` of ':' in value)
        ret.add(new String[]{"GAPS=1:A1aaaAaAA1aaAAAaa1a11a:aAaaAa-aaA1-", "GAPS", "1:A1aaaAaAA1aaAAAaa1a11a:aAaaAa-aaA1-"});
        
        // Strong abuse of cookie spec (lots of tspecials)
        ret.add(new String[]{"$Version=0; rToken=F_TOKEN''!--\"</a>=&{()}", "rToken", "F_TOKEN''!--\"</a>=&{()}"});
        
        return ret;
    }
    
    @Parameterized.Parameter
    public String rawHeader;
    
    @Parameterized.Parameter(1)
    public String expectedName;
    
    @Parameterized.Parameter(2)
    public String expectedValue;
    
    @Test
    public void testLenientBehavior()
    {
        CookieCutter cutter = new CookieCutter();
        cutter.addCookieField(rawHeader);
        Cookie[] cookies = cutter.getCookies();
        if (expectedName==null)
            assertThat("Cookies.length", cookies.length, is(0));
        else
        {
            assertThat("Cookies.length", cookies.length, is(1));
            assertThat("Cookie.name", cookies[0].getName(), is(expectedName));
            assertThat("Cookie.value", cookies[0].getValue(), is(expectedValue));
        }
    }
    
}
