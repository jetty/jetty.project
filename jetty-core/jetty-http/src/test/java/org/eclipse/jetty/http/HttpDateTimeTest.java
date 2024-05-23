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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HttpDateTimeTest
{
    public static Stream<Arguments> dateTimeValid()
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
        // RFC 1123 syntax with single digit day
        args.add(Arguments.of("Wed, 9 Jun 2021 10:18:14 GMT", "2021/06/09 10:18:14 GMT"));
        // RFC 850 with single digit day
        args.add(Arguments.of("Sun, 3-May-20 18:54:31 GMT", "2020/05/03 18:54:31 GMT"));
        // unix epoch (and zero time)
        args.add(Arguments.of("Thu, 01 Jan 1970 00:00:00 GMT", "1970/01/01 00:00:00 GMT"));
        // extra spaces
        args.add(Arguments.of(" Wed,  22  Aug  1984  13:14:15  GMT", "1984/08/22 13:14:15 GMT"));
        // seemingly invalid day (looks negative, but it isn't, as '-' is a delimiter per spec)
        args.add(Arguments.of("Thu, -2 Nov 2017 13:37:22 GMT", "2017/11/02 13:37:22 GMT"));
        // year zero (which is a valid year, assumed to be the year 2000 per spec)
        args.add(Arguments.of("Thu, 22 Nov 0000 23:45:12 GMT", "2000/11/22 23:45:12 GMT"));
        args.add(Arguments.of("Sun, 03-May-00 18:54:31 GMT", "2000/05/03 18:54:31 GMT"));
        // unexpected timezone (the timezone is ignored per spec)
        args.add(Arguments.of("Sun, 06 Nov 1994 08:49:37 PST", "1994/11/06 08:49:37 GMT"));
        // long weekday (weekday is ignored per spec)
        args.add(Arguments.of("Wednesday, 09 Jun 2021 10:18:14 GMT", "2021/06/09 10:18:14 GMT"));
        // date time before unix epoch
        args.add(Arguments.of("Fri, 13 Mar 1964 11:22:33 GMT", "1964/03/13 11:22:33 GMT"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("dateTimeValid")
    public void testParseValid(String input, String expected)
    {
        ZonedDateTime actual = HttpDateTime.parse(input);
        DateTimeFormatterBuilder formatter = new DateTimeFormatterBuilder();
        String actualStr = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss O").format(actual);
        assertEquals(expected, actualStr);
    }

    public static Stream<Arguments> dateTimeInvalid()
    {
        List<Arguments> args = new ArrayList<>();

        // Preferred RFC 1123 syntax
        // - invalid Year
        args.add(Arguments.of("Sun, 06 Nov 65535 08:49:37 GMT", "Missing [year]"));
        args.add(Arguments.of("Thu, 14 Oct 1535 01:02:00 GMT", "Too far in past [year]"));
        args.add(Arguments.of("Wed, 09 Jun -123 10:18:14 GMT", "Missing [year]"));
        // - no year
        args.add(Arguments.of("Wed, 09 Jun 10:18:14 GMT", "Missing [year]"));

        // - invalid day
        args.add(Arguments.of("Tue, 00 Apr 2024 17:28:27 GMT", "Invalid [day]"));
        args.add(Arguments.of("Tue, 31 Feb 2020 17:28:27 GMT", "Invalid date/time"));
        // - long form month
        args.add(Arguments.of("Wed, 22 August 1984 01:02:03 GMT", "Missing [month]"));
        // - no day
        args.add(Arguments.of("Thu, Nov 2017 13:37:22 GMT", "Missing [day]"));
        // - single digit hour
        args.add(Arguments.of("Wed, 09 Jun 2021 2:18:14 GMT", "Missing [time]"));

        // - no time
        args.add(Arguments.of("Thu, 01 Jan 1970 GMT", "Missing [time]"));
        // - invalid time (negative hour)
        args.add(Arguments.of("Thu,  2 Nov 2017 -3:14:15 GMT", "Missing [time]"));
        // - invalid time (negative minute)
        args.add(Arguments.of("Thu,  2 Nov 2017 13:-4:15 GMT", "Missing [time]"));
        // - invalid time (negative second)
        args.add(Arguments.of("Thu,  2 Nov 2017 13:14:-5 GMT", "Missing [time]"));

        // Obsolete RFC 850 syntax
        // - invalid year
        args.add(Arguments.of("Sun, 03-May-65535 18:54:31 GMT", "Missing [year]"));
        // - no year
        args.add(Arguments.of("Tue, 16-Apr- 17:28:27 GMT", "Missing [year]"));
        // - invalid day
        args.add(Arguments.of("Sunday, 00-Nov-94 08:49:37 GMT", "Invalid [day]"));
        // - long form month
        args.add(Arguments.of("Wednesday, 22-August-84 01:02:03 GMT", "Missing [month]"));
        // - single digit hour
        args.add(Arguments.of("Sunday, 01-Nov-94 8:49:37 GMT", "Missing [time]"));

        // - no day (the '94' is parsed as day as its the first 2 digit number in the string)
        args.add(Arguments.of("Sun, Nov-94 08:49:37 GMT", "Missing [year]"));

        // - no time
        args.add(Arguments.of("Mon, 05-May-2014 GMT", "Missing [time]"));
        // - bad time (negative hour)
        args.add(Arguments.of("Thu, 02-May-2013 -3:14:15 GMT", "Missing [time]"));
        // - bad time (negative minute)
        args.add(Arguments.of("Thu, 02-May-2013 13:-4:15 GMT", "Missing [time]"));
        // - bad time (negative second)
        args.add(Arguments.of("Thu, 02-May-2013 13:14:-5 GMT", "Missing [time]"));
        // - bad time (no seconds) - will not find time
        args.add(Arguments.of("Thu, 02-May-2013 13:14 GMT", "Missing [time]"));
        // - bad time (am/pm) - will not find time, and will not understand the AM/PM
        args.add(Arguments.of("Thu, 02-May-2013 13:14 PM GMT", "Missing [time]"));

        // Obsolete ANSI C's asctime() format
        // - invalid year
        args.add(Arguments.of("Sun Nov  6 08:49:37 65535", "Missing [year]"));

        // Horribly bad Date/Time formats
        args.add(Arguments.of("3%~", "Missing [month]"));
        args.add(Arguments.of("3%~ GMT", "Missing [month]"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("dateTimeInvalid")
    public void testParseInvalid(String input, String expectedMsg)
    {
        IllegalArgumentException syntaxException = assertThrows(
            IllegalArgumentException.class, () -> HttpDateTime.parse(input)
        );
        assertThat(syntaxException.getMessage(), containsString(expectedMsg));
    }

    @ParameterizedTest
    @MethodSource("dateTimeInvalid")
    public void testParseToEpochInvalid(String input, String expectedMsg)
    {
        assertThat(HttpDateTime.parseToEpoch(input), is(-1L));
    }

    @Test
    public void testParseToEpochBeforeEpoch()
    {
        long epoch = HttpDateTime.parseToEpoch("Fri, 13 Mar 1964 11:22:33 GMT");
        assertThat(epoch, is(-183127047000L));
    }

    @Test
    public void testFormatZonedDateTime()
    {
        // When "Back to the Future" released
        ZonedDateTime zonedDateTime = ZonedDateTime.of(1984, 7, 3, 8, 10, 30, 0, ZoneId.of("US/Pacific"));
        String actual = HttpDateTime.format(zonedDateTime);
        assertThat(actual, is("Tue, 3 Jul 1984 15:10:30 GMT"));
    }

    @Test
    public void testFormatInstant()
    {
        // When "Tron" released
        long epochMillis = 395054120000L;
        Instant instant = Instant.ofEpochMilli(epochMillis);
        String actual = HttpDateTime.format(instant);
        assertThat(actual, is("Fri, 9 Jul 1982 09:15:20 GMT"));
    }
}
