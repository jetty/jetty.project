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

package org.eclipse.jetty.server;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.http.HttpDateTime;
import org.eclipse.jetty.http.RFC6265SetCookieParser;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
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
        assertNull(HttpCookieUtils.getSameSiteDefault(context));
        
        //test good value for default as SameSite enum
        context.setAttribute(HttpCookieUtils.SAME_SITE_DEFAULT_ATTRIBUTE, SameSite.LAX);
        assertEquals(SameSite.LAX, HttpCookieUtils.getSameSiteDefault(context));
        
        //test good value for default as String
        context.setAttribute(HttpCookieUtils.SAME_SITE_DEFAULT_ATTRIBUTE, "NONE");
        assertEquals(SameSite.NONE, HttpCookieUtils.getSameSiteDefault(context));
        
        //test case for default as String
        context.setAttribute(HttpCookieUtils.SAME_SITE_DEFAULT_ATTRIBUTE, "sTrIcT");
        assertEquals(SameSite.STRICT, HttpCookieUtils.getSameSiteDefault(context));
        
        //test bad value for default as String
        context.setAttribute(HttpCookieUtils.SAME_SITE_DEFAULT_ATTRIBUTE, "fooBAR");
        assertThrows(IllegalStateException.class,
            () -> HttpCookieUtils.getSameSiteDefault(context));
    }

    @Test
    public void testMatchCookie()
    {
        //match with header string   
        assertTrue(HttpCookieUtils.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "domain", "path"));
        assertFalse(HttpCookieUtils.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "something", "domain", "path"));
        assertFalse(HttpCookieUtils.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "realm", "path"));
        assertFalse(HttpCookieUtils.match("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "domain", "street"));
        
        //match including set-cookie:, this is really testing the java.net.HttpCookie parser, but worth throwing in there
        assertTrue(HttpCookieUtils.match("Set-Cookie: everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; SameSite=Lax; Foo=Bar",
            "everything", "domain", "path"));
        
        //match via cookie
        HttpCookie httpCookie = HttpCookie.from("everything", "value", 0, Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain", HttpCookie.PATH_ATTRIBUTE, "path", HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0), HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true), HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true), HttpCookie.COMMENT_ATTRIBUTE, "comment"));
        assertTrue(HttpCookieUtils.match(httpCookie, "everything", "domain", "path"));
        assertFalse(HttpCookieUtils.match(httpCookie, "something", "domain", "path"));
        assertFalse(HttpCookieUtils.match(httpCookie, "everything", "realm", "path"));
        assertFalse(HttpCookieUtils.match(httpCookie, "everything", "domain", "street"));
    }

    public record SetCookieCase(HttpCookie httpCookie,
        String expectedRFC6265SetCookie,
        String expectedRFC2965SetCookie)
    {
    }

    public static Stream<SetCookieCase> setCookieProvider()
    {
        return Stream.of(
            new SetCookieCase(HttpCookie.from("null", null),
                "null=",
                "null=;Version=1"),
            new SetCookieCase(HttpCookie.build("null", null).build(),
                "null=",
                "null=;Version=1"),
            new SetCookieCase(HttpCookie.from("null", null, -1,
                    Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, "-1",
                        HttpCookie.HTTP_ONLY_ATTRIBUTE, "false",
                        HttpCookie.SECURE_ATTRIBUTE, "false")),
                "null=",
                "null="),
            new SetCookieCase(HttpCookie.build("null", null)
                    .attribute(HttpCookie.MAX_AGE_ATTRIBUTE, "-1")
                    .attribute(HttpCookie.HTTP_ONLY_ATTRIBUTE, "false")
                    .attribute(HttpCookie.SECURE_ATTRIBUTE, "false")
                    .build(),
                "null=",
                "null=;Version=1"),
            new SetCookieCase(HttpCookie.from("minimal", "value"),
                "minimal=value",
                "minimal=value"),
            new SetCookieCase(HttpCookie.build("minimal", "value").build(),
                "minimal=value",
                "minimal=value"),
            new SetCookieCase(HttpCookie.from("minimal", "value", -1,
                Map.of(HttpCookie.MAX_AGE_ATTRIBUTE, "-1",
                    HttpCookie.HTTP_ONLY_ATTRIBUTE, "false",
                    HttpCookie.SECURE_ATTRIBUTE, "false")),
                "minimal=value",
                "minimal=value"),
            new SetCookieCase(HttpCookie.build("minimal", "value")
                .attribute(HttpCookie.MAX_AGE_ATTRIBUTE, "-1")
                .attribute(HttpCookie.HTTP_ONLY_ATTRIBUTE, "false")
                .attribute(HttpCookie.SECURE_ATTRIBUTE, "false")
                .build(),
                "minimal=value",
                "minimal=value"),
            // Values with special characters
            new SetCookieCase(HttpCookie.from("name", "value%="),
                "name=value%=",
                "name=value%="),
            new SetCookieCase(HttpCookie.build("name", "value%=").build(),
                "name=value%=",
                "name=value%="),
            new SetCookieCase(HttpCookie.from("name", "value%=", -1, Map.of()),
                "name=value%=",
                "name=value%="),
            // test cookies with same name, domain and path
            new SetCookieCase(HttpCookie.build("everything", "something")
                    .domain("domain")
                    .path("path").build(),
                "everything=something; Path=path; Domain=domain",
                "everything=something;Domain=domain;Path=path"),
            new SetCookieCase(HttpCookie.from("everything", "something", -1,
                Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain",
                    HttpCookie.PATH_ATTRIBUTE, "path",
                    HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0),
                    HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true))),
                "everything=something; Path=path; Domain=domain; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly",
                "everything=something;Domain=domain;Path=path;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly"),
            new SetCookieCase(HttpCookie.build("everything", "something")
                    .attribute(HttpCookie.DOMAIN_ATTRIBUTE, "domain")
                    .attribute(HttpCookie.PATH_ATTRIBUTE, "path")
                    .attribute(HttpCookie.MAX_AGE_ATTRIBUTE, "0")
                    .attribute(HttpCookie.HTTP_ONLY_ATTRIBUTE, "true")
                    .attribute(HttpCookie.SECURE_ATTRIBUTE, "true")
                    .build(),
                "everything=something; Path=path; Domain=domain; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly",
                "everything=something;Domain=domain;Path=path;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly"),
            new SetCookieCase(HttpCookie.from("everything", "value", -1,
                Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain",
                    HttpCookie.PATH_ATTRIBUTE, "path",
                    HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0),
                    HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true))),
                "everything=value; Path=path; Domain=domain; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly",
                "everything=value;Domain=domain;Path=path;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly"),
            new SetCookieCase(HttpCookie.from("everything", "something", 0,
                Map.of(HttpCookie.DOMAIN_ATTRIBUTE, "domain",
                    HttpCookie.PATH_ATTRIBUTE, "path",
                    HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(0),
                    HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.COMMENT_ATTRIBUTE, "noncomment")),
                "everything=something; Path=path; Domain=domain; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly" /* RFC6265 produces no Comment attribute */,
                "everything=something;Version=1;Domain=domain;Path=path;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=noncomment"),
            // Tests of SameSite attribute
            new SetCookieCase(HttpCookie.from("everything", "value",
                Map.of(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.NONE.getAttributeValue())),
                "everything=value; Secure; SameSite=None",
                "everything=value;Secure;SameSite=None"),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .secure(true)
                    .sameSite(SameSite.NONE)
                    .build(),
                "everything=value; Secure; SameSite=None",
                "everything=value;Secure;SameSite=None"),
            new SetCookieCase(HttpCookie.from("everything", "value",
                Map.of(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.LAX.getAttributeValue())),
                "everything=value; Secure; SameSite=Lax",
                "everything=value;Secure;SameSite=Lax"),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .attribute(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true))
                    .attribute(HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.LAX.getAttributeValue())
                    .build(),
                "everything=value; Secure; SameSite=Lax",
                "everything=value;Secure;SameSite=Lax"),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .secure(true)
                    .sameSite(SameSite.LAX)
                    .build(),
                "everything=value; Secure; SameSite=Lax",
                "everything=value;Secure;SameSite=Lax"),
            new SetCookieCase(HttpCookie.from("everything", "value",
                Map.of(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.STRICT.getAttributeValue())),
                "everything=value; Secure; SameSite=Strict",
                "everything=value;Secure;SameSite=Strict"),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .attribute(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true))
                    .attribute(HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.STRICT.getAttributeValue())
                    .build(),
                "everything=value; Secure; SameSite=Strict",
                "everything=value;Secure;SameSite=Strict"),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .secure(true)
                    .sameSite(SameSite.STRICT)
                    .build(),
                "everything=value; Secure; SameSite=Strict",
                "everything=value;Secure;SameSite=Strict"),
            // Tests of Partitioned attribute
            new SetCookieCase(HttpCookie.from("everything", "value",
                Map.of(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.STRICT.getAttributeValue(),
                    HttpCookie.PARTITIONED_ATTRIBUTE, "")),
                "everything=value; Secure; Partitioned; SameSite=Strict",
                "everything=value;Secure;Partitioned;SameSite=Strict"),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .attribute(HttpCookie.SECURE_ATTRIBUTE, "true")
                    .attribute(HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.STRICT.getAttributeValue())
                    .attribute(HttpCookie.PARTITIONED_ATTRIBUTE, "")
                    .build(),
                "everything=value; Secure; Partitioned; SameSite=Strict",
                "everything=value;Secure;Partitioned;SameSite=Strict"),
            new SetCookieCase(HttpCookie.from("everything", "value",
                Map.of(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SAME_SITE_ATTRIBUTE, SameSite.STRICT.getAttributeValue(),
                    HttpCookie.PARTITIONED_ATTRIBUTE, Boolean.toString(true))),
                "everything=value; Secure; Partitioned; SameSite=Strict",
                "everything=value;Secure;Partitioned;SameSite=Strict"),
            // Tests of arbitrary attributes
            new SetCookieCase(HttpCookie.from("everything", "value", -1,
                Map.of(HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true),
                    HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true),
                    "Other", "attribute",
                    "Single", "")),
                "everything=value; Secure; HttpOnly; Other=attribute; Single",
                "everything=value;Secure;HttpOnly" /* no arbitrary attributes in older RFCs */),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .attribute(HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(true))
                    .attribute(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(true))
                    .attribute("Other", "attribute")
                    .attribute("Single", "")
                    .build(),
                "everything=value; Secure; HttpOnly; Other=attribute; Single",
                "everything=value;Secure;HttpOnly" /* no arbitrary attributes in older RFCs */),
            new SetCookieCase(HttpCookie.build("everything", "value")
                    .httpOnly(true)
                    .secure(true)
                    .attribute("Other", "attribute")
                    .attribute("Single", "")
                    .build(),
                "everything=value; Secure; HttpOnly; Other=attribute; Single",
                "everything=value;Secure;HttpOnly" /* no arbitrary attributes in older RFCs */)
        );
    }

    /**
     * Cookies that are SO FAR out of spec that they fail in some way or another.
     * Normally due to invalid characters, spacing, quoting, or similar things.
     */
    public static Stream<SetCookieCase> setCookieViolationProvider()
    {
        return Stream.of(
            // Values with spaces (not valid RFC6265)
            new SetCookieCase(HttpCookie.from("name", "v a l u e"),
                null,
                "name=\"v a l u e\";Version=1"),
            new SetCookieCase(HttpCookie.build("name", "v a l u e").build(),
                null,
                "name=\"v a l u e\";Version=1"),
            new SetCookieCase(HttpCookie.from("name", "v a l u e", -1, Map.of()),
                null,
                "name=\"v a l u e\""),
            new SetCookieCase(HttpCookie.build("ev erything", "va lue")
                .domain("do main")
                .path("pa th")
                .comment("co mment")
                .build(),
                null,
                "\"ev erything\"=\"va lue\";Version=1;Domain=\"do main\";Path=\"pa th\";Comment=\"co mment\""),
            // JSON values (not valid RFC6265)
            new SetCookieCase(HttpCookie.from("json", "{\"services\":[\"cwa\",  \"aa\"]}"),
                null,
                "json=\"{\\\"services\\\":[\\\"cwa\\\",  \\\"aa\\\"]}\";Version=1"),
            new SetCookieCase(HttpCookie.build("json", "{\"services\":[\"cwa\",  \"aa\"]}").build(),
                null,
                "json=\"{\\\"services\\\":[\\\"cwa\\\",  \\\"aa\\\"]}\";Version=1"),
            new SetCookieCase(HttpCookie.from("json", "{\"services\":[\"cwa\",  \"aa\"]}", -1, Map.of()),
                null,
                "json=\"{\\\"services\\\":[\\\"cwa\\\",  \\\"aa\\\"]}\"")
        );
    }

    @ParameterizedTest
    @MethodSource("setCookieProvider")
    public void testRFC2965SetCookie(SetCookieCase setCookieCase)
    {
        String actualSetCookie = HttpCookieUtils.getRFC2965SetCookie(setCookieCase.httpCookie());
        assertEquals(setCookieCase.expectedRFC2965SetCookie(), actualSetCookie);
    }

    @ParameterizedTest
    @MethodSource("setCookieViolationProvider")
    public void testRFC2965SetCookieViolation(SetCookieCase setCookieCase)
    {
        String actualSetCookie = HttpCookieUtils.getRFC2965SetCookie(setCookieCase.httpCookie());
        assertEquals(setCookieCase.expectedRFC2965SetCookie(), actualSetCookie);
    }

    @ParameterizedTest
    @MethodSource("setCookieProvider")
    public void testRFC6265SetCookie(SetCookieCase setCookieCase)
    {
        String actualSetCookie = HttpCookieUtils.getRFC6265SetCookie(setCookieCase.httpCookie());
        assertEquals(setCookieCase.expectedRFC6265SetCookie(), actualSetCookie);
    }

    @ParameterizedTest
    @MethodSource("setCookieProvider")
    public void testRFC2965SetCookieParse(SetCookieCase setCookieCase)
    {
        RFC6265SetCookieParser setCookieParser = new RFC6265SetCookieParser();
        HttpCookie cookie = setCookieParser.parse(setCookieCase.expectedRFC2965SetCookie());
        assertSameCookie(cookie, setCookieCase.httpCookie);
    }

    @ParameterizedTest
    @MethodSource("setCookieProvider")
    public void testRFC6265SetCookieParse(SetCookieCase setCookieCase)
    {
        RFC6265SetCookieParser setCookieParser = new RFC6265SetCookieParser();
        HttpCookie cookie = setCookieParser.parse(setCookieCase.expectedRFC6265SetCookie());
        assertSameCookie(cookie, setCookieCase.httpCookie);
    }

    private void assertSameCookie(HttpCookie cookie, HttpCookie httpCookie)
    {
        assertThat("cookie.name", cookie.getName(), is(httpCookie.getName()));
        String expectedValue = httpCookie.getValue();
        expectedValue = StringUtil.unquote(expectedValue == null ? "" : expectedValue);
        assertThat("cookie.value", cookie.getValue(), is(expectedValue));
        assertThat("cookie.domain", cookie.getDomain(), is(httpCookie.getDomain()));
        assertThat("cookie.path", cookie.getPath(), is(httpCookie.getPath()));
        assertThat("cookie.maxAge", cookie.getMaxAge(), is(httpCookie.getMaxAge()));
        assertThat("cookie.sameSite", cookie.getSameSite(), is(httpCookie.getSameSite()));
        assertThat("cookie.secure", cookie.isSecure(), is(httpCookie.isSecure()));
        assertThat("cookie.httpOnly", cookie.isHttpOnly(), is(httpCookie.isHttpOnly()));
        assertThat("cookie.partitioned", cookie.isPartitioned(), is(httpCookie.isPartitioned()));
        Instant expectedExpires = httpCookie.getExpires();
        if (expectedExpires == null && httpCookie.getMaxAge() == 0)
            expectedExpires = Instant.EPOCH;
        assertThat("cookie.expire", cookie.getExpires(), is(expectedExpires));
    }

    public static Stream<HttpCookie> setCookieMaxAge1Provider()
    {
        return Stream.of(
            // Using attributes directly
            HttpCookie.from("everything", "value",
                Map.of(HttpCookie.PATH_ATTRIBUTE, "/path",
                    HttpCookie.MAX_AGE_ATTRIBUTE, Long.toString(1))),
            // Using builder
            HttpCookie.build("everything", "value").path("/path").maxAge(1).build()
        );
    }

    @ParameterizedTest
    @MethodSource("setCookieMaxAge1Provider")
    public void testSetRFC2965SetCookieMaxAge1(HttpCookie httpCookie)
    {
        Instant now = Instant.now();
        String actualSetCookie = HttpCookieUtils.getRFC2965SetCookie(httpCookie);
        Pattern pattern = Pattern.compile("^everything=value;Path=/path;Expires=(.*);Max-Age=1$");
        Matcher matcher = pattern.matcher(actualSetCookie);
        assertTrue(matcher.matches(), "Actual Cookie doesn't match expected pattern");
        String expires = matcher.group(1);
        ZonedDateTime datetime = HttpDateTime.parse(expires);
        long minutes = Duration.between(now, datetime.toInstant()).toMinutes();
        assertThat(minutes, lessThan(2L));
    }

    @ParameterizedTest
    @MethodSource("setCookieMaxAge1Provider")
    public void testSetRFC6265SetCookieMaxAge1(HttpCookie httpCookie)
    {
        Instant now = Instant.now();
        String actualSetCookie = HttpCookieUtils.getRFC6265SetCookie(httpCookie);
        Pattern pattern = Pattern.compile("^everything=value; Path=/path; Expires=(.*); Max-Age=1$");
        Matcher matcher = pattern.matcher(actualSetCookie);
        assertTrue(matcher.matches(), "Actual Cookie doesn't match expected pattern: " + actualSetCookie);
        String expires = matcher.group(1);
        ZonedDateTime datetime = HttpDateTime.parse(expires);
        long minutes = Duration.between(now, datetime.toInstant()).toMinutes();
        assertThat(minutes, lessThan(2L));
    }

    public static Stream<HttpCookie> setCookieExpiresNowProvider()
    {
        return Stream.of(
            // Using attributes directly
            HttpCookie.from("everything", "value",
                Map.of(HttpCookie.PATH_ATTRIBUTE, "/path",
                    HttpCookie.EXPIRES_ATTRIBUTE, HttpCookie.formatExpires(Instant.now()))),
            // Using builder
            HttpCookie.build("everything", "value").path("/path").expires(Instant.now()).build()
        );
    }

    @ParameterizedTest
    @MethodSource("setCookieExpiresNowProvider")
    public void testSetRFC2965SetCookieExpiresNow(HttpCookie httpCookie)
    {
        Instant now = Instant.now();
        String actualSetCookie = HttpCookieUtils.getRFC2965SetCookie(httpCookie);
        Pattern pattern = Pattern.compile("^everything=value;Path=/path;Expires=(.*)$");
        Matcher matcher = pattern.matcher(actualSetCookie);
        assertTrue(matcher.matches(), "Actual Cookie doesn't match expected pattern: " + actualSetCookie);
        String expires = matcher.group(1);
        ZonedDateTime datetime = HttpDateTime.parse(expires);
        long minutes = Duration.between(now, datetime.toInstant()).toMinutes();
        assertThat(minutes, lessThan(2L));
    }

    @ParameterizedTest
    @MethodSource("setCookieExpiresNowProvider")
    public void testSetRFC6265SetCookieExpiresNow(HttpCookie httpCookie)
    {
        Instant now = Instant.now();
        String actualSetCookie = HttpCookieUtils.getRFC6265SetCookie(httpCookie);
        Pattern pattern = Pattern.compile("^everything=value; Path=/path; Expires=(.*)$");
        Matcher matcher = pattern.matcher(actualSetCookie);
        assertTrue(matcher.matches(), "Actual Cookie doesn't match expected pattern: " + actualSetCookie);
        String expires = matcher.group(1);
        ZonedDateTime datetime = HttpDateTime.parse(expires);
        long minutes = Duration.between(now, datetime.toInstant()).toMinutes();
        assertThat(minutes, lessThan(2L));
    }

    public static Stream<HttpCookie> setCookieExpiresEpochMaxAgeOneHourProvider()
    {
        String epoch = "Thu, 01 Jan 1970 00:00:00 GMT";
        return Stream.of(
            // Using attributes directly
            HttpCookie.from("everything", "value",
                Map.of(HttpCookie.PATH_ATTRIBUTE, "/path",
                    HttpCookie.MAX_AGE_ATTRIBUTE, "3600",
                    HttpCookie.EXPIRES_ATTRIBUTE, epoch)),
            // Using builder
            HttpCookie.build("everything", "value").path("/path").maxAge(3600).expires(Instant.EPOCH).build()
        );
    }

    @ParameterizedTest
    @MethodSource("setCookieExpiresEpochMaxAgeOneHourProvider")
    public void testSetRFC6265SetCookieExpiresEpochMaxAgeOneHour(HttpCookie httpCookie)
    {
        String actualSetCookie = HttpCookieUtils.getRFC6265SetCookie(httpCookie);
        Pattern pattern = Pattern.compile("^everything=value; Path=/path; Expires=(.*); Max-Age=3600$");
        Matcher matcher = pattern.matcher(actualSetCookie);
        assertTrue(matcher.matches(), "Actual Cookie doesn't match expected pattern: " + actualSetCookie);
        assertEquals(Instant.EPOCH, httpCookie.getExpires());
        assertEquals(3600L, httpCookie.getMaxAge());
    }

    @ParameterizedTest
    @MethodSource("setCookieExpiresEpochMaxAgeOneHourProvider")
    public void testSetRFC2965SetCookieExpiresEpochMaxAgeOneHour(HttpCookie httpCookie)
    {
        String actualSetCookie = HttpCookieUtils.getRFC2965SetCookie(httpCookie);
        Pattern pattern = Pattern.compile("^everything=value;Path=/path;Expires=(.*);Max-Age=3600$");
        Matcher matcher = pattern.matcher(actualSetCookie);
        assertTrue(matcher.matches(), "Actual Cookie doesn't match expected pattern: " + actualSetCookie);
        assertEquals(Instant.EPOCH, httpCookie.getExpires());
        assertEquals(3600L, httpCookie.getMaxAge());
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
                HttpCookieUtils.getRFC6265SetCookie(httpCookie);
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
                HttpCookieUtils.getRFC6265SetCookie(httpCookie);
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

    @Test
    public void testBuilderSimple()
    {
        HttpCookie httpCookie = HttpCookie.build("name", "value").build();
        assertThat(httpCookie.getName(), equalToIgnoringCase("name"));
        assertThat(httpCookie.getValue(), equalTo("value"));
        assertThat(httpCookie.getVersion(), equalTo(0));
        assertThat(httpCookie.getAttributes(), anEmptyMap());
    }

    @Test
    public void testBuilderNull()
    {
        HttpCookie httpCookie = HttpCookie.build("name", "value")
            .attribute(null, null)
            .comment(null)
            .domain(null)
            .httpOnly(false)
            .secure(false)
            .path(null)
            .build();
        assertThat(httpCookie.getName(), equalToIgnoringCase("name"));
        assertThat(httpCookie.getValue(), equalTo("value"));
        assertThat(httpCookie.getVersion(), equalTo(0));
        assertThat(httpCookie.getAttributes(), anEmptyMap());
    }

    @Test
    public void testBuilderFull()
    {
        HttpCookie httpCookie = HttpCookie.build("name", "value")
            .attribute("some", "value")
            .comment("comment")
            .domain("domain")
            .httpOnly(true)
            .maxAge(42)
            .secure(true)
            .path("/path")
            .build();
        assertThat(httpCookie.getName(), equalToIgnoringCase("name"));
        assertThat(httpCookie.getValue(), equalTo("value"));
        assertThat(httpCookie.getVersion(), equalTo(0));
        assertThat(httpCookie.getAttributes().keySet(), containsInAnyOrder(
            "some",
            HttpCookie.COMMENT_ATTRIBUTE,
            HttpCookie.DOMAIN_ATTRIBUTE,
            HttpCookie.HTTP_ONLY_ATTRIBUTE,
            HttpCookie.MAX_AGE_ATTRIBUTE,
            HttpCookie.SECURE_ATTRIBUTE,
            HttpCookie.PATH_ATTRIBUTE));
        assertThat(httpCookie.getAttributes().values(), containsInAnyOrder(
            "value",
            Boolean.TRUE.toString(),
            Boolean.TRUE.toString(),
            "comment",
            "domain",
            "42",
            "/path"));
    }

    @Test
    public void testJavaNetHttpCookie()
    {
        java.net.HttpCookie cookie = new java.net.HttpCookie("name", "value");
        cookie.setVersion(1);
        cookie.setComment("comment");
        cookie.setDomain("domain");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(42);
        cookie.setPath("/path");
        cookie.setSecure(true);

        HttpCookie httpCookie = HttpCookie.from(cookie);

        assertThat(httpCookie.getName(), equalTo("name"));
        assertThat(httpCookie.getValue(), equalTo("value"));
        assertThat(httpCookie.getVersion(), equalTo(1));
        assertThat(httpCookie.getDomain(), equalTo("domain"));
        assertThat(httpCookie.getMaxAge(), equalTo(42L));
        assertThat(httpCookie.isSecure(), equalTo(true));

        assertThat(httpCookie.getAttributes().keySet(), containsInAnyOrder(
            HttpCookie.COMMENT_ATTRIBUTE,
            HttpCookie.DOMAIN_ATTRIBUTE,
            HttpCookie.HTTP_ONLY_ATTRIBUTE,
            HttpCookie.MAX_AGE_ATTRIBUTE,
            HttpCookie.SECURE_ATTRIBUTE,
            HttpCookie.PATH_ATTRIBUTE));
        assertThat(httpCookie.getAttributes().values(), containsInAnyOrder(
            Boolean.TRUE.toString(),
            Boolean.TRUE.toString(),
            "comment",
            "domain",
            "42",
            "/path"));

        java.net.HttpCookie cookie2 = HttpCookie.asJavaNetHttpCookie(httpCookie);
        assertEquals(cookie, cookie2);
    }
}
