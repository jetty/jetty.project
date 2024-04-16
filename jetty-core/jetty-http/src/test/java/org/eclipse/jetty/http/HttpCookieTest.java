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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
            Arguments.of("Expires", "blah", DateTimeParseException.class),
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

    public static Stream<Arguments> expiresDatesValid()
    {
        List<Arguments> args = new ArrayList<>();

        // Examples taken from:
        // - https://datatracker.ietf.org/doc/html/rfc9110#section-5.6.7
        // - RFC 6265
        // - issue discussions
        // - curl results from major players like cloudflare

        // Preferred RFC 1123 syntax
        args.add(Arguments.of("Wed, 09 Jun 2021 10:18:14 GMT", "2021/06/09 10:18:14 GMT"));
        args.add(Arguments.of("Sun, 06 Nov 1994 08:49:37 GMT", "1994/11/06 08:49:37 GMT"));
        args.add(Arguments.of("Tue, 16 Apr 2024 17:28:27 GMT", "2024/04/16 17:28:27 GMT"));
        args.add(Arguments.of("Thu,  2 Nov 2017 13:37:22 GMT", "2017/11/02 13:37:22 GMT"));

        // Obsolete RFC 850 syntax
        args.add(Arguments.of("Tue, 10-May-22 21:23:37 GMT", "2022/05/10 21:23:37 GMT"));
        args.add(Arguments.of("Sun, 03-May-20 18:54:31 GMT", "2020/05/03 18:54:31 GMT"));
        args.add(Arguments.of("Tue, 16-Apr-24 17:28:27 GMT", "2024/04/16 17:28:27 GMT"));
        args.add(Arguments.of("Sunday, 06-Nov-94 08:49:37 GMT", "1994/11/06 08:49:37 GMT"));
        args.add(Arguments.of("Sun, 06-Nov-94 08:49:37 GMT", "1994/11/06 08:49:37 GMT"));
        args.add(Arguments.of("Mon, 05-May-2014 13:13:45 GMT", "2014/05/05 13:13:45 GMT"));
        args.add(Arguments.of("Thu, 02-May-2013 13:14:16 GMT", "2013/05/02 13:14:16 GMT"));

        // Obsolete ANSI C's asctime() format
        args.add(Arguments.of("Sun Nov  6 08:49:37 1994", "1994/11/06 08:49:37 GMT"));
        args.add(Arguments.of("Tue Apr 16 09:59:47 0000", "2000/04/16 09:59:47 GMT"));
        args.add(Arguments.of("Tue Apr 16 09:59:47 00", "2000/04/16 09:59:47 GMT"));

        // Unusual formats seen elsewhere
        // unix epoch (and zero time)
        args.add(Arguments.of("Thu, 01 Jan 1970 00:00:00 GMT", "1970/01/01 00:00:00 GMT"));
        // seemingly invalid day (looks negative, but it isn't, as '-' is a delimiter per spec)
        args.add(Arguments.of("Thu, -2 Nov 2017 13:37:22 GMT", "2017/11/02 13:37:22 GMT"));
        // year zero (which is a valid year, assumed to be the year 2000 per spec)
        args.add(Arguments.of("Thu, 22 Nov 0000 23:45:12 GMT", "2000/11/22 23:45:12 GMT"));
        args.add(Arguments.of("Sun, 03-May-00 18:54:31 GMT", "2000/05/03 18:54:31 GMT"));
        // unexpected timezone (the timezone is ignored per spec)
        args.add(Arguments.of("Sun, 06 Nov 1994 08:49:37 PST", "1994/11/06 08:49:37 GMT"));
        // long weekday (weekday is ignored per spec)
        args.add(Arguments.of("Wednesday, 09 Jun 2021 10:18:14 GMT", "2021/06/09 10:18:14 GMT"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("expiresDatesValid")
    public void testParseExpires(String input, String expected)
    {
        Instant actual = HttpCookie.parseExpires(input);
        DateTimeFormatterBuilder formatter = new DateTimeFormatterBuilder();
        String actualStr = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss O").format(actual.atZone(ZoneId.of("GMT")));
        assertEquals(expected, actualStr);
    }

    public static Stream<Arguments> expiresDatesInvalid()
    {
        List<Arguments> args = new ArrayList<>();

        // Preferred RFC 1123 syntax
        // - invalid Year
        args.add(Arguments.of("Sun, 06 Nov 65535 08:49:37 GMT", "Missing [year]"));
        args.add(Arguments.of("Wed, 09 Jun -123 10:18:14 GMT", "Missing [year]"));
        // - no year
        args.add(Arguments.of("Wed, 09 Jun 10:18:14 GMT", "Missing [year]"));

        // - invalid day
        args.add(Arguments.of("Tue, 00 Apr 2024 17:28:27 GMT", "Invalid [day]"));
        // - no day
        args.add(Arguments.of("Thu, Nov 2017 13:37:22 GMT", "Missing [day]"));

        // - no time
        args.add(Arguments.of("Thu, 01 Jan 1970 GMT", "Missing [time]"));

        // Obsolete RFC 850 syntax
        // - invalid year
        args.add(Arguments.of("Sun, 03-May-65535 18:54:31 GMT", "Missing [year]"));
        // - no year
        args.add(Arguments.of("Tue, 16-Apr- 17:28:27 GMT", "Missing [year]"));
        // - invalid day
        args.add(Arguments.of("Sunday, 00-Nov-94 08:49:37 GMT", "Invalid [day]"));
        // - no day (the '94' is parsed as day)
        args.add(Arguments.of("Sun, Nov-94 08:49:37 GMT", "Missing [year]"));

        // - no time
        args.add(Arguments.of("Mon, 05-May-2014 GMT", "Missing [time]"));
        // - bad time (no seconds)
        args.add(Arguments.of("Thu, 02-May-2013 13:14 GMT", "Missing [time]"));
        // - bad time (am/pm)
        args.add(Arguments.of("Thu, 02-May-2013 13:14 PM GMT", "Missing [time]"));

        // Obsolete ANSI C's asctime() format
        // - invalid year
        args.add(Arguments.of("Sun Nov  6 08:49:37 65535", "Missing [year]"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("expiresDatesInvalid")
    public void testParseExpiresInvalid(String input, String expectedMsg)
    {
        HttpCookie.DateTimeSyntaxException syntaxException = assertThrows(
            HttpCookie.DateTimeSyntaxException.class, () ->
            HttpCookie.parseExpires(input)
        );
        assertThat(syntaxException.getMessage(), containsString(expectedMsg));
    }
}
