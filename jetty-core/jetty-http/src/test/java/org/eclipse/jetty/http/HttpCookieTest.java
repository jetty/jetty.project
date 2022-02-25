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

import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.util.AttributesMap;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpCookieTest
{
    @Test
    public void testDefaultSameSite()
    {
        AttributesMap context = new AttributesMap();
        
        //test null value for default
        assertNull(HttpCookie.getSameSiteDefault(context));
        
        //test good value for default as SameSite enum
        context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, SameSite.LAX);
        assertEquals(SameSite.LAX, HttpCookie.getSameSiteDefault(context));
        
        //test good value for default as String
        context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, "NONE");
        assertEquals(SameSite.NONE, HttpCookie.getSameSiteDefault(context));
        
        //test case for default as String
        context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, "sTrIcT");
        assertEquals(SameSite.STRICT, HttpCookie.getSameSiteDefault(context));
        
        //test bad value for default as String
        context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, "fooBAR");
        assertThrows(IllegalStateException.class,
            () -> HttpCookie.getSameSiteDefault(context));
    }

    @Test
    public void testConstructFromSetCookie()
    {
        HttpCookie cookie = new HttpCookie("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly");
    }

    @Test
    public void testSetRFC2965Cookie() throws Exception
    {
        HttpCookie httpCookie;

        httpCookie = new HttpCookie("null", null, null, null, -1, false, false, null, -1);
        assertEquals("null=", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("minimal", "value", null, null, -1, false, false, null, -1);
        assertEquals("minimal=value", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("everything", "something", "domain", "path", 0, true, true, "noncomment", 0);
        assertEquals("everything=something;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=noncomment", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, "comment", 0);
        assertEquals("everything=value;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("ev erything", "va lue", "do main", "pa th", 1, true, true, "co mment", 1);
        String setCookie = httpCookie.getRFC2965SetCookie();
        assertThat(setCookie, Matchers.startsWith("\"ev erything\"=\"va lue\";Version=1;Path=\"pa th\";Domain=\"do main\";Expires="));
        assertThat(setCookie, Matchers.endsWith(" GMT;Max-Age=1;Secure;HttpOnly;Comment=\"co mment\""));

        httpCookie = new HttpCookie("name", "value", null, null, -1, false, false, null, 0);
        setCookie = httpCookie.getRFC2965SetCookie();
        assertEquals(-1, setCookie.indexOf("Version="));
        httpCookie = new HttpCookie("name", "v a l u e", null, null, -1, false, false, null, 0);
        setCookie = httpCookie.getRFC2965SetCookie();

        httpCookie = new HttpCookie("json", "{\"services\":[\"cwa\",  \"aa\"]}", null, null, -1, false, false, null, -1);
        assertEquals("json=\"{\\\"services\\\":[\\\"cwa\\\",  \\\"aa\\\"]}\"", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("name", "value%=", null, null, -1, false, false, null, 0);
        setCookie = httpCookie.getRFC2965SetCookie();
        assertEquals("name=value%=", setCookie);
    }

    @Test
    public void testSetRFC6265Cookie() throws Exception
    {
        HttpCookie httpCookie;

        httpCookie = new HttpCookie("null", null, null, null, -1, false, false, null, -1);
        assertEquals("null=", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("minimal", "value", null, null, -1, false, false, null, -1);
        assertEquals("minimal=value", httpCookie.getRFC6265SetCookie());

        //test cookies with same name, domain and path
        httpCookie = new HttpCookie("everything", "something", "domain", "path", 0, true, true, null, -1);
        assertEquals("everything=something; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, null, -1);
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, null, -1, HttpCookie.SameSite.NONE);
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=None", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, null, -1, HttpCookie.SameSite.LAX);
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, null, -1, HttpCookie.SameSite.STRICT);
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Strict", httpCookie.getRFC6265SetCookie());
    }

    public static Stream<String> rfc6265BadNameSource()
    {
        return Stream.of(
            "\"name\"",
            "name\t",
            "na me",
            "name\u0082",
            "na\tme",
            "na;me",
            "{name}",
            "[name]",
            "\""
        );
    }

    @ParameterizedTest
    @MethodSource("rfc6265BadNameSource")
    public void testSetRFC6265CookieBadName(String badNameExample)
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () ->
            {
                HttpCookie httpCookie = new HttpCookie(badNameExample, "value", null, "/", 1, true, true, null, -1);
                httpCookie.getRFC6265SetCookie();
            });
        // make sure that exception mentions just how mad of a name it truly is
        assertThat("message", ex.getMessage(),
            allOf(
                // violation of Cookie spec
                containsString("RFC6265"),
                // violation of HTTP spec
                containsString("RFC2616")
            ));
    }

    public static Stream<String> rfc6265BadValueSource()
    {
        return Stream.of(
            "va\tlue",
            "\t",
            "value\u0000",
            "val\u0082ue",
            "va lue",
            "va;lue",
            "\"value",
            "value\"",
            "val\\ue",
            "val\"ue",
            "\""
        );
    }

    @ParameterizedTest
    @MethodSource("rfc6265BadValueSource")
    public void testSetRFC6265CookieBadValue(String badValueExample)
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () ->
            {
                HttpCookie httpCookie = new HttpCookie("name", badValueExample, null, "/", 1, true, true, null, -1);
                httpCookie.getRFC6265SetCookie();
            });
        assertThat("message", ex.getMessage(), containsString("RFC6265"));
    }

    public static Stream<String> rfc6265GoodNameSource()
    {
        return Stream.of(
            "name",
            "n.a.m.e",
            "na-me",
            "+name",
            "na*me",
            "na$me",
            "#name");
    }

    @ParameterizedTest
    @MethodSource("rfc6265GoodNameSource")
    public void testSetRFC6265CookieGoodName(String goodNameExample)
    {
        new HttpCookie(goodNameExample, "value", null, "/", 1, true, true, null, -1);
        // should not throw an exception
    }

    public static Stream<String> rfc6265GoodValueSource()
    {
        String[] goodValueExamples = {
            "value",
            "",
            null,
            "val=ue",
            "val-ue",
            "val/ue",
            "v.a.l.u.e"
        };
        return Stream.of(goodValueExamples);
    }

    @ParameterizedTest
    @MethodSource("rfc6265GoodValueSource")
    public void testSetRFC6265CookieGoodValue(String goodValueExample)
    {
        new HttpCookie("name", goodValueExample, null, "/", 1, true, true, null, -1);
        // should not throw an exception
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "__HTTP_ONLY__",
        "__HTTP_ONLY__comment",
        "comment__HTTP_ONLY__"
    })
    public void testIsHttpOnlyInCommentTrue(String comment)
    {
        assertTrue(HttpCookie.isHttpOnlyInComment(comment), "Comment \"" + comment + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "comment",
        "",
        "__",
        "__HTTP__ONLY__",
        "__http_only__",
        "HTTP_ONLY",
        "__HTTP__comment__ONLY__"
    })
    public void testIsHttpOnlyInCommentFalse(String comment)
    {
        assertFalse(HttpCookie.isHttpOnlyInComment(comment), "Comment \"" + comment + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "__SAME_SITE_NONE__",
        "__SAME_SITE_NONE____SAME_SITE_NONE__"
    })
    public void testGetSameSiteFromCommentNONE(String comment)
    {
        assertEquals(HttpCookie.getSameSiteFromComment(comment), HttpCookie.SameSite.NONE, "Comment \"" + comment + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "__SAME_SITE_LAX__",
        "__SAME_SITE_LAX____SAME_SITE_NONE__",
        "__SAME_SITE_NONE____SAME_SITE_LAX__",
        "__SAME_SITE_LAX____SAME_SITE_NONE__"
    })
    public void testGetSameSiteFromCommentLAX(String comment)
    {
        assertEquals(HttpCookie.getSameSiteFromComment(comment), HttpCookie.SameSite.LAX, "Comment \"" + comment + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "__SAME_SITE_STRICT__",
        "__SAME_SITE_NONE____SAME_SITE_STRICT____SAME_SITE_LAX__",
        "__SAME_SITE_STRICT____SAME_SITE_LAX____SAME_SITE_NONE__",
        "__SAME_SITE_STRICT____SAME_SITE_STRICT__"
    })
    public void testGetSameSiteFromCommentSTRICT(String comment)
    {
        assertEquals(HttpCookie.getSameSiteFromComment(comment), HttpCookie.SameSite.STRICT, "Comment \"" + comment + "\"");
    }

    /**
     * A comment that does not have a declared SamesSite attribute defined
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "__HTTP_ONLY__",
        "comment",
        // not jetty attributes
        "SameSite=None",
        "SameSite=Lax",
        "SameSite=Strict",
        // incomplete jetty attributes
        "SAME_SITE_NONE",
        "SAME_SITE_LAX",
        "SAME_SITE_STRICT",
    })
    public void testGetSameSiteFromCommentUndefined(String comment)
    {
        assertNull(HttpCookie.getSameSiteFromComment(comment), "Comment \"" + comment + "\"");
    }

    public static Stream<Arguments> getCommentWithoutAttributesSource()
    {
        return Stream.of(
            // normal - only attribute comment
            Arguments.of("__SAME_SITE_LAX__", null),
            // normal - no attribute comment
            Arguments.of("comment", "comment"),
            // mixed - attributes at end
            Arguments.of("comment__SAME_SITE_NONE__", "comment"),
            Arguments.of("comment__HTTP_ONLY____SAME_SITE_NONE__", "comment"),
            // mixed - attributes at start
            Arguments.of("__SAME_SITE_NONE__comment", "comment"),
            Arguments.of("__HTTP_ONLY____SAME_SITE_NONE__comment", "comment"),
            // mixed - attributes at start and end
            Arguments.of("__SAME_SITE_NONE__comment__HTTP_ONLY__", "comment"),
            Arguments.of("__HTTP_ONLY__comment__SAME_SITE_NONE__", "comment")
        );
    }

    @ParameterizedTest
    @MethodSource("getCommentWithoutAttributesSource")
    public void testGetCommentWithoutAttributes(String rawComment, String expectedComment)
    {
        String actualComment = HttpCookie.getCommentWithoutAttributes(rawComment);
        if (expectedComment == null)
        {
            assertNull(actualComment);
        }
        else
        {
            assertEquals(actualComment, expectedComment);
        }
    }

    @Test
    public void testGetCommentWithAttributes()
    {
        assertThat(HttpCookie.getCommentWithAttributes(null, false, null), nullValue());
        assertThat(HttpCookie.getCommentWithAttributes("", false, null), nullValue());
        assertThat(HttpCookie.getCommentWithAttributes("hello", false, null), is("hello"));

        assertThat(HttpCookie.getCommentWithAttributes(null, true, HttpCookie.SameSite.STRICT),
            is("__HTTP_ONLY____SAME_SITE_STRICT__"));
        assertThat(HttpCookie.getCommentWithAttributes("", true, HttpCookie.SameSite.NONE),
            is("__HTTP_ONLY____SAME_SITE_NONE__"));
        assertThat(HttpCookie.getCommentWithAttributes("hello", true, HttpCookie.SameSite.LAX),
            is("hello__HTTP_ONLY____SAME_SITE_LAX__"));

        assertThat(HttpCookie.getCommentWithAttributes("__HTTP_ONLY____SAME_SITE_LAX__", false, null), nullValue());
        assertThat(HttpCookie.getCommentWithAttributes("__HTTP_ONLY____SAME_SITE_LAX__", true, HttpCookie.SameSite.NONE),
            is("__HTTP_ONLY____SAME_SITE_NONE__"));
        assertThat(HttpCookie.getCommentWithAttributes("__HTTP_ONLY____SAME_SITE_LAX__hello", true, HttpCookie.SameSite.LAX),
            is("hello__HTTP_ONLY____SAME_SITE_LAX__"));
    }
}
