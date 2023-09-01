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

import java.time.format.DateTimeParseException;
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
    public static List<Arguments> validCookies()
    {
        return List.of(
            // Single cookie.
            Arguments.of("=", List.of()),
            Arguments.of("=B", List.of()),
            Arguments.of("=B; a", List.of()),
            Arguments.of("=B; a=", List.of()),
            Arguments.of("=B; a=v", List.of()),
            Arguments.of("A", List.of()),
            Arguments.of("A=", List.of(HttpCookie.build("A", "").build())),
            Arguments.of("A=; HttpOnly", List.of(HttpCookie.build("A", "").httpOnly(true).build())),
            Arguments.of("A=; a", List.of(HttpCookie.build("A", "").attribute("a", "").build())),
            Arguments.of("A=; a=", List.of(HttpCookie.build("A", "").attribute("a", "").build())),
            Arguments.of("A=; a=v", List.of(HttpCookie.build("A", "").attribute("a", "v").build())),
            Arguments.of("A=B", List.of(HttpCookie.build("A", "B").build())),
            Arguments.of("A=B; Secure", List.of(HttpCookie.build("A", "B").secure(true).build())),
            Arguments.of("A=B; a", List.of(HttpCookie.build("A", "B").attribute("a", "").build())),
            Arguments.of("A=B; a=", List.of(HttpCookie.build("A", "B").attribute("a", "").build())),
            Arguments.of("A=B; a=v", List.of(HttpCookie.build("A", "B").attribute("a", "v").build())),
            Arguments.of("A=B; Secure; Path=/", List.of(HttpCookie.build("A", "B").secure(true).path("/").build())),
            // Multiple cookies.
            Arguments.of("=,=", List.of()),
            Arguments.of("=B,=C", List.of()),
            Arguments.of("=B; b, =C", List.of()),
            Arguments.of("=B; b, =C; c", List.of()),
            Arguments.of("=B; b=, =C; c=", List.of()),
            Arguments.of("=B; b=2, =C; c=3", List.of()),
            Arguments.of("A, B", List.of()),
            Arguments.of("A=, B=", List.of(
                    HttpCookie.build("A", "").build(),
                    HttpCookie.build("B", "").build()
                )
            ),
            Arguments.of("A=1, B=2", List.of(
                    HttpCookie.build("A", "1").build(),
                    HttpCookie.build("B", "2").build()
                )
            ),
            Arguments.of("A=1; HttpOnly, B=2; Secure; Path=/", List.of(
                    HttpCookie.build("A", "1").httpOnly(true).build(),
                    HttpCookie.build("B", "2").secure(true).path("/").build()
                )
            ),
            // Quoted cookies.
            Arguments.of("A=\"1\"; HttpOnly, B=2; Secure; Path=\"/\"", List.of(
                    HttpCookie.build("A", "1").httpOnly(true).build(),
                    HttpCookie.build("B", "2").secure(true).path("/").build()
                )
            ),
            // Mixed valid and invalid cookies.
            Arguments.of("A=1; Expires=blah, B=2", List.of(HttpCookie.build("B", "2").build())),
            Arguments.of("A=1; Expires=blah; Path=/, B=2", List.of(HttpCookie.build("B", "2").build())),
            Arguments.of("A=1, B=2; Max-Age=blah, C=3", List.of(
                    HttpCookie.build("A", "1").build(),
                    HttpCookie.build("C", "3").build()
                )
            ),
            Arguments.of("A=1; HttpOnly=blah, B=2; SameSite=Lax, C=3; Secure=blah", List.of(
                    HttpCookie.build("B", "2").sameSite(HttpCookie.SameSite.LAX).build()
                )
            )
        );
    }

    @ParameterizedTest
    @MethodSource("validCookies")
    public void testParseValidCookies(String setCookieValue, List<HttpCookie> expectedCookies)
    {
        List<HttpCookie> cookies = HttpCookie.parse(setCookieValue);
        assertEquals(expectedCookies.size(), cookies.size());
        for (int i = 0; i < cookies.size(); ++i)
        {
            HttpCookie expected = expectedCookies.get(i);
            HttpCookie cookie = cookies.get(i);
            assertEquals(expected, cookie);
            assertThat(expected.getAttributes(), is(cookie.getAttributes()));
        }
    }

    public static List<Arguments> invalidCookies()
    {
        return List.of(
            Arguments.of("A=1; Expires=blah", DateTimeParseException.class),
            Arguments.of("A=1; HttpOnly=blah", IllegalArgumentException.class),
            Arguments.of("A=1; Max-Age=blah", NumberFormatException.class),
            Arguments.of("A=1; SameSite=blah", IllegalArgumentException.class),
            Arguments.of("A=1; Secure=blah", IllegalArgumentException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidCookies")
    public void testParseInvalidCookies(String setCookieValue, Class<? extends Throwable> failure)
    {
        assertThrows(failure, () -> HttpCookie.parse(setCookieValue));
    }
}
