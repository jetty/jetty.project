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

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.eclipse.jetty.util.StringUtil;

/**
 * ThreadLocal Date formatters for HTTP style dates.
 */
public class DateGenerator
{
    private static final TimeZone __GMT = TimeZone.getTimeZone("GMT");

    static
    {
        __GMT.setID("GMT");
    }

    static final String[] DAYS =
        {"Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    static final String[] MONTHS =
        {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec", "Jan"};

    private static final ThreadLocal<DateGenerator> __dateGenerator = new ThreadLocal<DateGenerator>()
    {
        @Override
        protected DateGenerator initialValue()
        {
            return new DateGenerator();
        }
    };

    public static final String __01Jan1970 = DateGenerator.formatDate(0);

    /**
     * Format HTTP date "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
     *
     * @param date the date in milliseconds
     * @return the formatted date
     */
    public static String formatDate(long date)
    {
        return __dateGenerator.get().doFormatDate(date);
    }

    /**
     * Format "EEE, dd-MMM-yyyy HH:mm:ss 'GMT'" for cookies
     *
     * @param buf the buffer to put the formatted date into
     * @param date the date in milliseconds
     */
    public static void formatCookieDate(StringBuilder buf, long date)
    {
        __dateGenerator.get().doFormatCookieDate(buf, date);
    }

    /**
     * Format "EEE, dd-MMM-yyyy HH:mm:ss 'GMT'" for cookies
     *
     * @param date the date in milliseconds
     * @return the formatted date
     */
    public static String formatCookieDate(long date)
    {
        StringBuilder buf = new StringBuilder(28);
        formatCookieDate(buf, date);
        return buf.toString();
    }

    private final StringBuilder buf = new StringBuilder(32);
    private final GregorianCalendar gc = new GregorianCalendar(__GMT);

    /**
     * Format HTTP date "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
     *
     * @param date the date in milliseconds
     * @return the formatted date
     */
    public String doFormatDate(long date)
    {
        buf.setLength(0);
        gc.setTimeInMillis(date);

        final int dayOfWeek = gc.get(Calendar.DAY_OF_WEEK);
        final int dayOfMonth = gc.get(Calendar.DAY_OF_MONTH);
        final int month = gc.get(Calendar.MONTH);
        final int fullYear = gc.get(Calendar.YEAR);
        final int century = fullYear / 100;
        final int year = fullYear % 100;

        final int hours = gc.get(Calendar.HOUR_OF_DAY);
        final int minutes = gc.get(Calendar.MINUTE);
        final int seconds = gc.get(Calendar.SECOND);

        buf.append(DAYS[dayOfWeek]);
        buf.append(',');
        buf.append(' ');
        StringUtil.append2digits(buf, dayOfMonth);

        buf.append(' ');
        buf.append(MONTHS[month]);
        buf.append(' ');
        StringUtil.append2digits(buf, century);
        StringUtil.append2digits(buf, year);

        buf.append(' ');
        StringUtil.append2digits(buf, hours);
        buf.append(':');
        StringUtil.append2digits(buf, minutes);
        buf.append(':');
        StringUtil.append2digits(buf, seconds);
        buf.append(" GMT");
        return buf.toString();
    }

    /**
     * Format "EEE, dd-MMM-yy HH:mm:ss 'GMT'" for cookies
     *
     * @param buf the buffer to format the date into
     * @param date the date in milliseconds
     */
    public void doFormatCookieDate(StringBuilder buf, long date)
    {
        gc.setTimeInMillis(date);

        final int dayOfWeek = gc.get(Calendar.DAY_OF_WEEK);
        final int dayOfMonth = gc.get(Calendar.DAY_OF_MONTH);
        final int month = gc.get(Calendar.MONTH);
        final int fullYear = gc.get(Calendar.YEAR);
        final int year = fullYear % 10000;

        final int epochSec = (int)((date / 1000) % (60 * 60 * 24));
        final int seconds = epochSec % 60;
        final int epoch = epochSec / 60;
        final int minutes = epoch % 60;
        final int hours = epoch / 60;

        buf.append(DAYS[dayOfWeek]);
        buf.append(',');
        buf.append(' ');
        StringUtil.append2digits(buf, dayOfMonth);

        buf.append('-');
        buf.append(MONTHS[month]);
        buf.append('-');
        StringUtil.append2digits(buf, year / 100);
        StringUtil.append2digits(buf, year % 100);

        buf.append(' ');
        StringUtil.append2digits(buf, hours);
        buf.append(':');
        StringUtil.append2digits(buf, minutes);
        buf.append(':');
        StringUtil.append2digits(buf, seconds);
        buf.append(" GMT");
    }
}
