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

import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.util.AttributesMap;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
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
    public void testMatchCookie()
    {
        //match with header string   
        assertTrue(HttpCookie.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "domain", "path"));
        assertFalse(HttpCookie.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "something", "domain", "path"));
        assertFalse(HttpCookie.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "realm", "path"));
        assertFalse(HttpCookie.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "domain", "street"));
        
        //match including set-cookie:, this is really testing the java.net.HttpCookie parser, but worth throwing in there
        assertTrue(HttpCookie.match("Set-Cookie: everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "domain", "path"));
        
        //match via cookie
        HttpCookie httpCookie = HttpCookie.from("everything", "value", 0, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.COMMENT_ATTRIBUTE, "comment"));
        assertTrue(HttpCookie.match(httpCookie, "everything", "domain", "path"));
        assertFalse(HttpCookie.match(httpCookie, "something", "domain", "path"));
        assertFalse(HttpCookie.match(httpCookie, "everything", "realm", "path"));
        assertFalse(HttpCookie.match(httpCookie, "everything", "domain", "street"));
    }

    @Test
    public void testSetRFC2965Cookie() throws Exception
    {
        HttpCookie httpCookie;

        httpCookie = HttpCookie.from("null", null, -1, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        assertEquals("null=", HttpCookie.getRFC2965SetCookie(httpCookie));

        httpCookie = HttpCookie.from("minimal", "value", -1, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        assertEquals("minimal=value", HttpCookie.getRFC2965SetCookie(httpCookie));

        httpCookie = HttpCookie.from("everything", "something", 0, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.COMMENT_ATTRIBUTE, "noncomment"));
        assertEquals("everything=something;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=noncomment", HttpCookie.getRFC2965SetCookie(httpCookie));

        httpCookie = HttpCookie.from("everything", "value", 0, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.COMMENT_ATTRIBUTE, "comment"));
        assertEquals("everything=value;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment", HttpCookie.getRFC2965SetCookie(httpCookie));

        httpCookie = HttpCookie.from("ev erything", "va lue", 1, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "do main", HttpCookie.PATH_ATTRIBUTE, "pa th", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.COMMENT_ATTRIBUTE, "co mment"));
        String setCookie = HttpCookie.getRFC2965SetCookie(httpCookie);
        assertThat(setCookie, Matchers.startsWith("\"ev erything\"=\"va lue\";Version=1;Path=\"pa th\";Domain=\"do main\";Expires="));
        assertThat(setCookie, Matchers.endsWith(" GMT;Max-Age=1;Secure;HttpOnly;Comment=\"co mment\""));

        httpCookie = HttpCookie.from("name", "value", 0, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        setCookie = HttpCookie.getRFC2965SetCookie(httpCookie);
        assertEquals(-1, setCookie.indexOf("Version="));
        httpCookie = HttpCookie.from("name", "v a l u e", 0, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        setCookie = HttpCookie.getRFC2965SetCookie(httpCookie);

        httpCookie = HttpCookie.from("json", "{\"services\":[\"cwa\",  \"aa\"]}", -1, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        assertEquals("json=\"{\\\"services\\\":[\\\"cwa\\\",  \\\"aa\\\"]}\"", HttpCookie.getRFC2965SetCookie(httpCookie));

        httpCookie = HttpCookie.from("name", "value%=", 0, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        setCookie = HttpCookie.getRFC2965SetCookie(httpCookie);
        assertEquals("name=value%=", setCookie);
    }

    @Test
    public void testSetRFC6265Cookie() throws Exception
    {
        HttpCookie httpCookie;

        httpCookie = HttpCookie.from("null", null, -1, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        assertEquals("null=", HttpCookie.getRFC6265SetCookie(httpCookie));

        httpCookie = HttpCookie.from("minimal", "value", -1, Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(-1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(false), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(false)));
        assertEquals("minimal=value", HttpCookie.getRFC6265SetCookie(httpCookie));

        //test cookies with same name, domain and path
        httpCookie = HttpCookie.from("everything", "something", -1, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true)));
        assertEquals("everything=something; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly", HttpCookie.getRFC6265SetCookie(httpCookie));

        httpCookie = HttpCookie.from("everything", "value", -1, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true)));
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly", HttpCookie.getRFC6265SetCookie(httpCookie));

        httpCookie = HttpCookie.from("everything", "value", Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.NONE.getAttributeValue()));
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=None", HttpCookie.getRFC6265SetCookie(httpCookie));

        httpCookie = HttpCookie.from("everything", "value", Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.LAX.getAttributeValue()));
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax", HttpCookie.getRFC6265SetCookie(httpCookie));

        httpCookie = HttpCookie.from("everything", "value", Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.STRICT.getAttributeValue()));
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Strict", HttpCookie.getRFC6265SetCookie(httpCookie));
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
                HttpCookie httpCookie = HttpCookie.from(badNameExample, "value", -1, Map.of(HttpCookie.PATH_ATTRIBUTE, "/", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true)));
                HttpCookie.getRFC6265SetCookie(httpCookie);
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
                HttpCookie httpCookie = HttpCookie.from("name", badValueExample, -1, Map.of(HttpCookie.PATH_ATTRIBUTE, "/", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true)));
                HttpCookie.getRFC6265SetCookie(httpCookie);
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
        HttpCookie.from(goodNameExample, "value", -1, Map.of(HttpCookie.PATH_ATTRIBUTE, "/", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true)));
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
        HttpCookie.from("name", goodValueExample, -1, Map.of(HttpCookie.PATH_ATTRIBUTE, "/", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(1), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true)));
        // should not throw an exception
    }
}
