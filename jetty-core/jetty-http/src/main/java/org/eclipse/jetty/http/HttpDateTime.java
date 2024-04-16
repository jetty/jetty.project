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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Objects;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Date/Time parsing and formatting.
 *
 * <p>
 *     Also covers RFC6265 Cookie Date parsing and formatting.
 * </p>
 */
public class HttpDateTime
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpDateTime.class);

    private static final Index<Integer> MONTH_CACHE = new Index.Builder<Integer>()
        .caseSensitive(false)
        // Note: Calendar.Month fields are zero based.
        .with("Jan", Calendar.JANUARY + 1)
        .with("Feb", Calendar.FEBRUARY + 1)
        .with("Mar", Calendar.MARCH + 1)
        .with("Apr", Calendar.APRIL + 1)
        .with("May", Calendar.MAY + 1)
        .with("Jun", Calendar.JUNE + 1)
        .with("Jul", Calendar.JULY + 1)
        .with("Aug", Calendar.AUGUST + 1)
        .with("Sep", Calendar.SEPTEMBER + 1)
        .with("Oct", Calendar.OCTOBER + 1)
        .with("Nov", Calendar.NOVEMBER + 1)
        .with("Dec", Calendar.DECEMBER + 1)
        .build();

    private HttpDateTime()
    {
    }

    /**
     * Similar to {@link #parse(String)} but returns unix epoch
     *
     * @param datetime the Date/Time to parse.
     * @return unix epoch in milliseconds, or -1 if unable to parse the input date/time
     */
    public static long parseToEpoch(String datetime)
    {
        try
        {
            Instant instant = parse(datetime);
            return instant.toEpochMilli();
        }
        catch (IllegalArgumentException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to parse Date/Time: {}", datetime, e);
            return -1;
        }
    }

    /**
     * <p>Parses a Date/Time value</p>
     *
     * <p>Supports the following Date/Time formats found in both
     *  <a href="https://datatracker.ietf.org/doc/html/rfc9110#name-date-time-formats">RFC 9110 (HTTP Semantics)</a> and
     *  <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.1.1">RFC 6265 (HTTP State Management Mechanism)</a>
     * </p>
     *
     * <ul>
     *     <li>{@code Sun, 06 Nov 1994 08:49:37 GMT} - RFC 1123 (preferred)</li>
     *     <li>{@code Sunday, 06-Nov-94 08:49:37 GMT} - RFC 850 (obsolete)</li>
     *     <li>{@code Sun Nov  6 08:49:37 1994} - ANSI C's {@code asctime()} format</li>
     * </ul>
     *
     * <p>
     *  Parsing is done according to the algorithm specified in
     *  <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.1.1">RFC6265: Section 5.1.1: Date</a>
     * </p>
     *
     * @param datetime a Date/Time string in a supported format
     * @return an {@link Instant} parsed from the given string
     * @throws IllegalArgumentException if unable to parse date/time
     */
    public static Instant parse(String datetime)
    {
        Objects.requireNonNull(datetime, "Date/Time string cannot be null");

        int year = -1;
        int month = -1;
        int day = -1;
        int hour = -1;
        int minute = -1;
        int second = -1;

        try
        {
            int tokenCount = 0;
            StringTokenizer tokenizer = new StringTokenizer(datetime, "\t" + // %x09
                " !\"#$%&'()*+,-./" + " + " + // %x20-2F
                ";<=>?@" + // %x3B-40
                "[\\]^_`" + // %x5B-60
                "{|}~" // %x7B-7E
            );
            while (tokenizer.hasMoreTokens())
            {
                String token = tokenizer.nextToken();
                // ensure we don't exceed the number of expected tokens.
                if (++tokenCount > 6)
                {
                    // This is a horribly bad syntax / format
                    throw new IllegalStateException("Too many delimiters for a Date/Time format");
                }

                if (token.isBlank())
                    continue; // skip blank tokens

                // RFC 6265 - Section 5.1.1 - Step 2.1 - time (00:00:00)
                if (hour == (-1) && token.length() == 8 && token.charAt(2) == ':' && token.charAt(5) == ':')
                {
                    second = StringUtil.toInt(token, 6);
                    minute = StringUtil.toInt(token, 3);
                    hour = StringUtil.toInt(token, 0);
                    continue;
                }

                // RFC 6265 - Section 5.1.1 - Step 2.2
                if (day == (-1) && token.length() <= 2)
                {
                    day = StringUtil.toInt(token, 0);
                    continue;
                }

                // RFC 6265 - Section 5.1.1 - Step 2.3
                if (month == (-1) && token.length() == 3)
                {
                    Integer m = MONTH_CACHE.getBest(token);
                    if (m != null)
                    {
                        month = m;
                        continue;
                    }
                }

                // RFC 6265 - Section 5.1.1 - Step 2.4
                if (year == (-1))
                {
                    if (token.length() <= 2)
                    {
                        year = StringUtil.toInt(token, 0);
                    }
                    else if (token.length() == 4)
                    {
                        year = StringUtil.toInt(token, 0);
                    }
                    continue;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignore: Unable to parse Date/Time", x);
        }

        // RFC 6265 - Section 5.1.1 - Step 3
        if ((year > 70) && (year <= 99))
            year += 1900;
        // RFC 6265 - Section 5.1.1 - Step 4
        if ((year >= 0) && (year <= 69))
            year += 2000;

        // RFC 6265 - Section 5.1.1 - Step 5
        if (day == (-1))
            throw new IllegalArgumentException("Missing [day]: " + datetime);
        if (month == (-1))
            throw new IllegalArgumentException("Missing [month]: " + datetime);
        if (year == (-1))
            throw new IllegalArgumentException("Missing [year]: " + datetime);
        if (hour == (-1))
            throw new IllegalArgumentException("Missing [time]: " + datetime);
        if (day < 1 || day > 31)
            throw new IllegalArgumentException("Invalid [day]: " + datetime);
        if (month < 1 || month > 31)
            throw new IllegalArgumentException("Invalid [month]: " + datetime);
        if (hour > 23)
            throw new IllegalArgumentException("Invalid [hour]: " + datetime);
        if (minute > 59)
            throw new IllegalArgumentException("Invalid [minute]: " + datetime);
        if (second > 59)
            throw new IllegalArgumentException("Invalid [second]: " + datetime);

        // RFC 6265 - Section 5.1.1 - Step 6
        ZonedDateTime dateTime = ZonedDateTime.of(year,
            month, day, hour, minute, second, 0,
            ZoneId.of("GMT"));

        // RFC 6265 - Section 5.1.1 - Step 7
        return dateTime.toInstant();
    }

    public static String format(Instant instant)
    {
        return DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(instant);
    }
}
