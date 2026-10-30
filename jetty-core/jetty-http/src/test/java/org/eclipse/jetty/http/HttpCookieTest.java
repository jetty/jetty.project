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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpCookieTest
{
    public static List<Arguments> cookies()
    {
        return List.of(
            Arguments.of("=", null),
            Arguments.of("=B", null),
            Arguments.of("=B; a", null),
            Arguments.of("=B; a=", null),
            Arguments.of("=B; a=v", null),
            Arguments.of("A", null),
            Arguments.of("A=", HttpCookie.build("A", "").build()),
            Arguments.of("A=; HttpOnly", HttpCookie.build("A", "").httpOnly(true).build()),
            Arguments.of("A=; a", HttpCookie.build("A", "").attribute("a", "").build()),
            Arguments.of("A=; a=", HttpCookie.build("A", "").attribute("a", "").build()),
            Arguments.of("A=; a=v", HttpCookie.build("A", "").attribute("a", "v").build()),
            Arguments.of("A=B", HttpCookie.build("A", "B").build()),
            Arguments.of("A= B", HttpCookie.build("A", "B").build()),
            Arguments.of("A =B", HttpCookie.build("A", "B").build()),
            Arguments.of(" A=B", HttpCookie.build("A", "B").build()),
            Arguments.of(" A= B", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Secure", HttpCookie.build("A", "B").secure(true).build()),
            Arguments.of("A=B; Expires=Thu, 01 Jan 1970 00:00:00 GMT", HttpCookie.build("A", "B").expires(Instant.EPOCH).build()),
            Arguments.of("A=B; a", HttpCookie.build("A", "B").attribute("a", "").build()),
            Arguments.of("A=B; a=", HttpCookie.build("A", "B").attribute("a", "").build()),
            Arguments.of("A=B; a=v", HttpCookie.build("A", "B").attribute("a", "v").build()),
            Arguments.of("A=B; Secure; Path=/", HttpCookie.build("A", "B").secure(true).path("/").build()),
            Arguments.of("A=B; Max-Age=-2", HttpCookie.build("A", "B").maxAge(0).build()),
            Arguments.of("A=B; Max-Age=-1", HttpCookie.build("A", "B").maxAge(0).build()),
            Arguments.of("A=B; Max-Age=0", HttpCookie.build("A", "B").maxAge(0).build()),
            Arguments.of("A=B; Max-Age=1", HttpCookie.build("A", "B").maxAge(1).build()),
            Arguments.of("A=B; Max-Age=10", HttpCookie.build("A", "B").maxAge(10).build()),
            // Quoted cookie.
            Arguments.of("A=\"1\"", HttpCookie.build("A", "1").build()),
            Arguments.of("A=\"1\"; HttpOnly", HttpCookie.build("A", "1").httpOnly(true).build()),
            Arguments.of(" A = \"1\" ; a = v", HttpCookie.build("A", "1").attribute("a", "v").build()),
            Arguments.of(" A = \"1\" ; a = \"v\"; Secure", HttpCookie.build("A", "1").attribute("a", "v").secure(true).build()),
            Arguments.of(" A = \"1\" ; Path= \"/\"", HttpCookie.build("A", "1").path("/").build()),
            Arguments.of(" A = \"1\" ; Expires= \"Thu, 01 Jan 1970 00:00:00 GMT\"", HttpCookie.build("A", "1").expires(Instant.EPOCH).build()),
            // Invalid cookie.
            Arguments.of("A=\"1\" Bad", null),
            // RFC 6265 section 5.2: the name-value-pair (before the first ';') lacks '=', ignore entirely.
            Arguments.of("foo; Max-Age=0", null),
            Arguments.of("foo; Path=/", null),
            Arguments.of("foo; Secure", null),
            Arguments.of(" ; a=b", null),
            // Secure (section 5.2.5), HttpOnly (section 5.2.6), and SameSite (RFC 6265bis section 5.4.7)
            // are flag/enumerated attributes; an invalid value keeps the cookie (the flag is enabled,
            // and an unknown SameSite falls back to the default) rather than rejecting the cookie.
            Arguments.of("A=1; HttpOnly=blah", HttpCookie.build("A", "1").httpOnly(true).build()),
            Arguments.of("A=1; Secure=blah", HttpCookie.build("A", "1").secure(true).build()),
            Arguments.of("A=1; SameSite=blah", HttpCookie.build("A", "1").build()),
            Arguments.of("A=1; SameSite=blah; Secure", HttpCookie.build("A", "1").secure(true).build()),
            // An explicit true/false is honored; a valueless flag is enabled.
            Arguments.of("A=B; Secure=true", HttpCookie.build("A", "B").secure(true).build()),
            Arguments.of("A=B; Secure=false", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; HttpOnly=false", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Partitioned=blah", HttpCookie.build("A", "B").partitioned(true).build()),
            // SameSite None/Lax/Strict are honored; a blank value falls back to the default.
            Arguments.of("A=B; SameSite=None", HttpCookie.build("A", "B").sameSite(HttpCookie.SameSite.NONE).build()),
            Arguments.of("A=B; SameSite=Lax", HttpCookie.build("A", "B").sameSite(HttpCookie.SameSite.LAX).build()),
            Arguments.of("A=B; SameSite=Strict", HttpCookie.build("A", "B").sameSite(HttpCookie.SameSite.STRICT).build()),
            Arguments.of("A=B; SameSite=", HttpCookie.build("A", "B").build()),
            // RFC 6265, section 5.2.x: a malformed attribute value causes only that cookie-av to be
            // ignored; the rest of the cookie is retained.
            // Max-Age (section 5.2.2): an optional '-' followed by one or more digits, else ignored.
            Arguments.of("A=B; Max-Age", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=blah", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=ABC", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=NaN", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=-123.45", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=0.00", HttpCookie.build("A", "B").build()),
            // A leading '+' is not permitted (only an optional '-'), and a lone '-' has no digits; both ignored.
            Arguments.of("A=B; Max-Age=+10", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=-", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=\"blah\"", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Max-Age=blah; Secure", HttpCookie.build("A", "B").secure(true).build()),
            // An all-digit Max-Age that overflows long is clamped, not dropped (section 5.2.2).
            Arguments.of("A=B; Max-Age=99999999999999999999", HttpCookie.build("A", "B").maxAge(Long.MAX_VALUE).build()),
            Arguments.of("A=B; Max-Age=-99999999999999999999", HttpCookie.build("A", "B").maxAge(0).build()),
            // Expires (section 5.2.1): must parse as a cookie-date, else ignored.
            Arguments.of("A=B; Expires=blah", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Expires=", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Expires=blah; HttpOnly", HttpCookie.build("A", "B").httpOnly(true).build()),
            // Domain (section 5.2.3): an empty value is ignored.
            Arguments.of("A=B; Domain=", HttpCookie.build("A", "B").build()),
            Arguments.of("A=B; Domain=; Secure", HttpCookie.build("A", "B").secure(true).build()),
            // Weird cookie.
            Arguments.of("A=1; Domain=example.org; Domain=domain.com", HttpCookie.build("A", "1").domain("domain.com").build()),
            Arguments.of("A=1; Path=/; Path=/ctx", HttpCookie.build("A", "1").path("/ctx").build())
        );
    }

    @ParameterizedTest
    @MethodSource("cookies")
    public void testParseCookies(String setCookieValue, HttpCookie expectedCookie)
    {
        SetCookieParser parser = SetCookieParser.newInstance();
        HttpCookie parsed = parser.parse(setCookieValue);
        assertEquals(expectedCookie, parsed);
        if (expectedCookie != null)
            assertThat(expectedCookie.getAttributes(), is(parsed.getAttributes()));
    }

    public static List<Arguments> invalidAttributes()
    {
        // Secure/HttpOnly/Partitioned are RFC 6265 flags and are lenient (see testFlagAttributesAreLenient
        // and HttpCookie.isAttributeTrue), so they are not listed here as invalid.
        return List.of(
            Arguments.of("Expires", "blah", IllegalArgumentException.class),
            Arguments.of("Max-Age", "blah", IllegalArgumentException.class),
            Arguments.of("SameSite", "blah", IllegalArgumentException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidAttributes")
    public void testParseInvalidAttributes(String name, String value, Class<? extends Throwable> failure)
    {
        assertThrows(failure, () -> HttpCookie.build("A", "1")
            .attribute(name, value));
    }

    @Test
    public void testRemoveSpecialAttributes()
    {
        HttpCookie cookie = HttpCookie.build("name", "value")
            .expires(Instant.now())
            .httpOnly(true)
            .maxAge(1000)
            .sameSite(HttpCookie.SameSite.STRICT)
            .secure(true)
            .partitioned(true)
            .build();

        assertThat(cookie.getExpires(), notNullValue());
        assertThat(cookie.isHttpOnly(), is(true));
        assertThat(cookie.getMaxAge(), is(1000L));
        assertThat(cookie.getSameSite(), is(HttpCookie.SameSite.STRICT));
        assertThat(cookie.isSecure(), is(true));
        assertThat(cookie.isPartitioned(), is(true));
        // The positive Max-Age takes precedence over the past Expires (RFC 6265 section 5.3), so not expired.
        assertThat(cookie.isExpired(), is(false));

        HttpCookie noAttributes = HttpCookie.build(cookie)
            .expires(null)
            .httpOnly(false)
            .maxAge(-1)
            .sameSite(null)
            .secure(false)
            .partitioned(false)
            .build();
        assertThat(noAttributes.getExpires(), nullValue());
        assertThat(noAttributes.isHttpOnly(), is(false));
        assertThat(noAttributes.getMaxAge(), is(-1L));
        assertThat(noAttributes.getSameSite(), nullValue());
        assertThat(noAttributes.isSecure(), is(false));
        assertThat(noAttributes.isPartitioned(), is(false));
        // No Max-Age and no Expires: a session cookie, which does not expire on its own.
        assertThat(noAttributes.isExpired(), is(false));

        noAttributes = HttpCookie.build(cookie)
            .attribute(HttpCookie.EXPIRES_ATTRIBUTE, null)
            .attribute(HttpCookie.HTTP_ONLY_ATTRIBUTE, null)
            .attribute(HttpCookie.MAX_AGE_ATTRIBUTE, null)
            .attribute(HttpCookie.SAME_SITE_ATTRIBUTE, null)
            .attribute(HttpCookie.SECURE_ATTRIBUTE, null)
            .attribute(HttpCookie.PARTITIONED_ATTRIBUTE, null)
            .build();
        assertThat(noAttributes.getExpires(), nullValue());
        assertThat(noAttributes.isHttpOnly(), is(false));
        assertThat(noAttributes.getMaxAge(), is(-1L));
        assertThat(noAttributes.getSameSite(), nullValue());
        assertThat(noAttributes.isSecure(), is(false));
        assertThat(noAttributes.isPartitioned(), is(false));
        // No Max-Age and no Expires: a session cookie, which does not expire on its own.
        assertThat(noAttributes.isExpired(), is(false));
    }

    @Test
    public void testNegativeMaxAge()
    {
        // A negative Max-Age set via the builder means "not specified" (a session cookie),
        // so no Max-Age attribute is stored and getMaxAge() reports -1 (absent).
        HttpCookie cookie = HttpCookie.build("A", "B").maxAge(-1).build();
        assertThat(cookie.getAttributes().size(), is(0));
        assertThat(cookie.getMaxAge(), is(-1L));
        // An absent Max-Age (and no Expires) is a session cookie, not an expired one.
        assertThat(cookie.isExpired(), is(false));

        // Negative max-age in builder usage, means to remove the attribute.
        cookie = HttpCookie.build("A", "B").attribute("max-age", "-2").build();
        assertThat(cookie.getAttributes().size(), is(0));
        assertThat(cookie.getMaxAge(), is(-1L)); // absent
        assertThat(cookie.isExpired(), is(false));
    }

    @Test
    public void testAttributeMaxAgeOverflowIsClamped()
    {
        // An all-digit Max-Age that overflows a long is clamped (as the RFC 6265 parser does) rather
        // than throwing: a positive value becomes a far-future (non-expiring) cookie.
        HttpCookie cookie = HttpCookie.build("A", "B").attribute("max-age", "99999999999999999999").build();
        assertThat(cookie.getMaxAge(), is(Long.MAX_VALUE));
        assertThat(cookie.isExpired(), is(false));

        // A negative overflow stays negative ("not specified"), i.e. a session cookie.
        cookie = HttpCookie.build("A", "B").attribute("max-age", "-99999999999999999999").build();
        assertThat(cookie.getAttributes().containsKey(HttpCookie.MAX_AGE_ATTRIBUTE), is(false));
        assertThat(cookie.getMaxAge(), is(-1L));
    }

    @Test
    public void testAttributeMaxAgeRejectsLeadingPlusAndNonDigits()
    {
        // RFC 6265 section 5.2.2 permits only an optional leading '-'; a leading '+' is rejected.
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.build("A", "B").attribute("max-age", "+10").build());
        // A lone '-' and non-numeric values are rejected.
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.build("A", "B").attribute("max-age", "-").build());
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.build("A", "B").attribute("max-age", "blah").build());
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.build("A", "B").attribute("max-age", "0.00").build());
    }

    @Test
    public void testBuildFromJavaNetSessionCookieIsNotExpired()
    {
        // java.net.HttpCookie defaults to a max-age of -1 (MAX_AGE_UNSPECIFIED), i.e. a session cookie.
        java.net.HttpCookie javaNetCookie = new java.net.HttpCookie("n", "v");
        HttpCookie cookie = HttpCookie.build(javaNetCookie).build();
        assertFalse(cookie.getAttributes().containsKey(HttpCookie.MAX_AGE_ATTRIBUTE));
        assertThat(cookie.getMaxAge(), is(-1L));
        assertFalse(cookie.isExpired());
    }

    @Test
    public void testBuildFromJavaNetSessionCookieMaxAgeZeroIsExpired()
    {
        java.net.HttpCookie javaNetCookie = new java.net.HttpCookie("n", "v");
        javaNetCookie.setMaxAge(0);
        HttpCookie cookie = HttpCookie.build(javaNetCookie).build();
        assertTrue(cookie.getAttributes().containsKey(HttpCookie.MAX_AGE_ATTRIBUTE));
        assertThat(cookie.getMaxAge(), is(0L));
        assertTrue(cookie.isExpired());
    }

    @Test
    public void testBuildFromJavaNetSessionCookieMaxAgeNegativeIsNotExpired()
    {
        java.net.HttpCookie javaNetCookie = new java.net.HttpCookie("n", "v");
        javaNetCookie.setMaxAge(-1);
        HttpCookie cookie = HttpCookie.build(javaNetCookie).build();
        assertFalse(cookie.getAttributes().containsKey(HttpCookie.MAX_AGE_ATTRIBUTE));
        assertThat(cookie.getMaxAge(), is(-1L));
        assertFalse(cookie.isExpired());
    }

    @Test
    public void testParseBlankMaxAgeIsIgnored()
    {
        // RFC 6265 section 5.2.2: a Max-Age whose value is not a valid integer must be ignored,
        // leaving a session cookie rather than an expired one.
        SetCookieParser parser = SetCookieParser.newInstance();
        HttpCookie cookie = parser.parse("n=v; Max-Age");
        assertTrue(cookie.getAttributes().isEmpty());
        assertThat(cookie.getMaxAge(), is(-1L));
        assertFalse(cookie.isExpired());
    }

    @Test
    public void testParseNegativeMaxAgeIsExpired()
    {
        // RFC 6265 section 5.2.2: a Max-Age of zero or less means the cookie is already expired.
        // The parser normalizes a negative Max-Age to 0 and keeps the cookie, so the parsed cookie
        // must report isExpired() == true.
        SetCookieParser parser = SetCookieParser.newInstance();
        HttpCookie cookie = parser.parse("A=B; Max-Age=-2");
        assertThat(cookie.getMaxAge(), is(0L));
        assertTrue(cookie.isExpired());
    }

    @Test
    public void testFromMapInvalidAttributesThrow()
    {
        // from(Map) validates known attribute values; an invalid value throws IllegalArgumentException.
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.from("n", "v", Map.of("Max-Age", "blah")));
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.from("n", "v", Map.of("Expires", "blah")));
        assertThrows(IllegalArgumentException.class, () -> HttpCookie.from("n", "v", Map.of("SameSite", "blah")));

        // The Max-Age failure is a descriptive IllegalArgumentException, not a bare NumberFormatException.
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
            () -> HttpCookie.from("n", "v", Map.of("Max-Age", "blah")));
        assertTrue(x.getMessage().contains("Invalid Max-Age"), "message: " + x.getMessage());
    }

    @Test
    public void testAttributesAreLenient()
    {
        // Secure/HttpOnly/Partitioned are RFC 6265 flags: present and not "false" means enabled, any
        // other value included. See HttpCookie.isAttributeTrue(String). They do not throw on non-boolean
        // values, consistent with the wire parser and the attribute getters.
        assertTrue(HttpCookie.from("n", "v", Map.of("Secure", "blah")).isSecure());
        assertTrue(HttpCookie.from("n", "v", Map.of("HttpOnly", "yes")).isHttpOnly());
        assertTrue(HttpCookie.from("n", "v", Map.of("Partitioned", "1")).isPartitioned());
        // An explicit "false" (ASCII, case-insensitive) disables the flag.
        assertFalse(HttpCookie.from("n", "v", Map.of("Secure", "false")).isSecure());
        assertFalse(HttpCookie.from("n", "v", Map.of("HttpOnly", "FALSE")).isHttpOnly());
    }

    @Test
    public void testIsAttributeTrue()
    {
        assertFalse(HttpCookie.isAttributeTrue(null));
        assertTrue(HttpCookie.isAttributeTrue(""));
        assertTrue(HttpCookie.isAttributeTrue("true"));
        assertTrue(HttpCookie.isAttributeTrue("anything"));
        assertFalse(HttpCookie.isAttributeTrue("false"));
        assertFalse(HttpCookie.isAttributeTrue("False"));
    }

    public static List<Arguments> expiries()
    {
        Instant past = Instant.now().minusSeconds(3600);
        Instant future = Instant.now().plusSeconds(3600);
        return List.of(
            // No Max-Age and no Expires: a session cookie, which does not expire on its own.
            Arguments.of(HttpCookie.build("A", "B").build(), false),
            // Max-Age, where 0 means expired.
            Arguments.of(HttpCookie.build("A", "B").maxAge(0).build(), true),
            Arguments.of(HttpCookie.build("A", "B").maxAge(1).build(), false),
            // A negative Max-Age means "not specified" (a session cookie), not "already expired".
            Arguments.of(HttpCookie.build("A", "B").maxAge(-1).build(), false),
            Arguments.of(HttpCookie.build("A", "B").maxAge(-5).build(), false),
            // Expires only.
            Arguments.of(HttpCookie.build("A", "B").expires(past).build(), true),
            Arguments.of(HttpCookie.build("A", "B").expires(future).build(), false),
            // A positive Max-Age takes precedence over Expires (RFC 6265 section 5.3).
            Arguments.of(HttpCookie.build("A", "B").maxAge(3600).expires(past).build(), false),
            Arguments.of(HttpCookie.build("A", "B").maxAge(0).expires(future).build(), true),
            // A negative Max-Age is not stored, so a session cookie with a future Expires is governed by Expires.
            Arguments.of(HttpCookie.build("A", "B").maxAge(-1).expires(future).build(), false)
        );
    }

    @ParameterizedTest
    @MethodSource("expiries")
    public void testIsExpired(HttpCookie cookie, boolean expired)
    {
        assertThat(cookie.isExpired(), is(expired));
    }

    @Test
    public void testExpiresNowNoMaxAge()
    {
        Instant now = Instant.now();
        HttpCookie cookie = HttpCookie.build("name", "value").expires(now).build();
        // This cookie now has an attribute called 'Expires' which is a string of the Instant.
        String expectedExpires = HttpDateTime.format(now);
        assertThat(cookie.getAttributes().get("Expires"), is(expectedExpires));
        Instant expectedExpiresInstant = HttpDateTime.parse(cookie.getAttributes().get("Expires")).toInstant();
        assertEquals(cookie.getExpires(), expectedExpiresInstant);
        assertTrue(cookie.isExpired());
        assertEquals(-1L, cookie.getMaxAge());
    }

    @Test
    public void testExpiresEpochMaxAgeOneHour()
    {
        HttpCookie cookie = HttpCookie.build("name", "value").expires(Instant.EPOCH).maxAge(3600).build();
        String expectedExpires = "Thu, 01 Jan 1970 00:00:00 GMT";
        assertThat(cookie.getAttributes().get("Expires"), is(expectedExpires));
        Instant expectedExpiresInstant = HttpDateTime.parse(cookie.getAttributes().get("Expires")).toInstant();
        assertEquals(cookie.getExpires(), expectedExpiresInstant);
        // The positive Max-Age takes precedence over the past Expires (RFC 6265 section 5.3), so not expired.
        assertFalse(cookie.isExpired());
        assertEquals(3600, cookie.getMaxAge());
    }

    @Test
    public void testMaxAgeZero()
    {
        HttpCookie cookie = HttpCookie.build("name", "value").maxAge(0).build();
        assertThat(cookie.getAttributes().get("Expires"), nullValue());
        assertTrue(cookie.isExpired());
        assertEquals(0, cookie.getMaxAge());
    }

    @Test
    public void testMaxAgeOneHour()
    {
        HttpCookie cookie = HttpCookie.build("name", "value").maxAge(3600).build();
        assertThat(cookie.getAttributes().get("Expires"), nullValue());
        assertFalse(cookie.isExpired());
        assertEquals(3600, cookie.getMaxAge());
    }
}
