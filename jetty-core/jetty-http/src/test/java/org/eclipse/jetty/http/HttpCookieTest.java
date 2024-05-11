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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            // Quoted cookie.
            Arguments.of("A=\"1\"", HttpCookie.build("A", "1").build()),
            Arguments.of("A=\"1\"; HttpOnly", HttpCookie.build("A", "1").httpOnly(true).build()),
            Arguments.of(" A = \"1\" ; a = v", HttpCookie.build("A", "1").attribute("a", "v").build()),
            Arguments.of(" A = \"1\" ; a = \"v\"; Secure", HttpCookie.build("A", "1").attribute("a", "v").secure(true).build()),
            Arguments.of(" A = \"1\" ; Path= \"/\"", HttpCookie.build("A", "1").path("/").build()),
            Arguments.of(" A = \"1\" ; Expires= \"Thu, 01 Jan 1970 00:00:00 GMT\"", HttpCookie.build("A", "1").expires(Instant.EPOCH).build()),
            // Invalid cookie.
            Arguments.of("A=\"1\" Bad", null),
            Arguments.of("A=1; Expires=blah", null),
            Arguments.of("A=1; Expires=blah; HttpOnly", null),
            Arguments.of("A=1; HttpOnly=blah", null),
            Arguments.of("A=1; Max-Age=blah", null),
            Arguments.of("A=1; SameSite=blah", null),
            Arguments.of("A=1; SameSite=blah; Secure", null),
            Arguments.of("A=1; Secure=blah", null),
            Arguments.of("A=1; Max-Age=\"blah\"", null),
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
        return List.of(
            Arguments.of("Expires", "blah", IllegalArgumentException.class),
            Arguments.of("HttpOnly", "blah", IllegalArgumentException.class),
            Arguments.of("Max-Age", "blah", NumberFormatException.class),
            Arguments.of("SameSite", "blah", IllegalArgumentException.class),
            Arguments.of("Secure", "blah", IllegalArgumentException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidAttributes")
    public void testParseInvalidAttributes(String name, String value, Class<? extends Throwable> failure)
    {
        assertThrows(failure, () -> HttpCookie.build("A", "1")
            .attribute(name, value));
    }

}
