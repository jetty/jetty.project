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

package org.eclipse.jetty.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Computes String representations of Dates then caches the results so
 * that subsequent requests within the same second will be fast.
 * <p>
 * When formatting with the cache, sub second formatting will not be precise
 * and may use a cached result.
 * <p>
 * If consecutive calls are frequently very different, then this
 * may be a little slower than a normal DateFormat.
 * <p>
 * @see DateTimeFormatter for date formatting patterns.
 */
public class DateCache
{
    public static final String DEFAULT_FORMAT = "EEE MMM dd HH:mm:ss zzz yyyy";

    private final String _formatString;
    private final DateTimeFormatter _tzFormat;
    private final ZoneId _zoneId;

    private volatile Tick _tick;

    public static class Tick
    {
        private final long _seconds;
        private final String _string;

        public Tick(long seconds, String string)
        {
            _seconds = seconds;
            _string = string;
        }

        public long getSeconds()
        {
            return _seconds;
        }

        public String getString()
        {
            return _string;
        }
    }

    /**
     * Make a DateCache that will use a default format.
     * The default format generates the same results as Date.toString().
     */
    public DateCache()
    {
        this(DEFAULT_FORMAT);
    }

    /**
     * Make a DateCache that will use the given format.
     *
     * @param format the format to use
     */
    public DateCache(String format)
    {
        this(format, null, TimeZone.getDefault());
    }

    public DateCache(String format, Locale l)
    {
        this(format, l, TimeZone.getDefault());
    }

    public DateCache(String format, Locale l, String tz)
    {
        this(format, l, TimeZone.getTimeZone(tz));
    }

    public DateCache(String format, Locale l, TimeZone tz)
    {
        if (l == null)
            _tzFormat = DateTimeFormatter.ofPattern(format);
        else
            _tzFormat = DateTimeFormatter.ofPattern(format, l);

        _formatString = format;
        _zoneId = tz.toZoneId();
        _tzFormat.withZone(_zoneId);
        _tick = null;
    }

    public TimeZone getTimeZone()
    {
        return TimeZone.getTimeZone(_zoneId);
    }

    /**
     * Format a date according to our stored formatter.
     * If it happens to be in the same second as the last
     * formatNow call, then the format is reused.
     *
     * @param inDate the Date.
     * @return Formatted date.
     */
    public String format(Date inDate)
    {
        return formatTick(inDate.getTime())._string;
    }

    /**
     * Format a date according to our stored formatter.
     * If it happens to be in the same second as the last
     * formatNow call, then the format is reused.
     *
     * @param inDate the date in milliseconds since unix epoch.
     * @return Formatted date.
     */
    public String format(long inDate)
    {
        return formatTick(inDate)._string;
    }

    /**
     * Format a date according to our stored formatter without using the cache.
     *
     * @param inDate the Date.
     * @return Formatted date.
     */
    public String formatWithoutCache(Date inDate)
    {
        return ZonedDateTime.ofInstant(inDate.toInstant(), _zoneId).format(_tzFormat);
    }

    /**
     * Format a date according to our stored formatter without using the cache.
     *
     * @param inDate the date in milliseconds since unix epoch.
     * @return Formatted date.
     */
    public String formatWithoutCache(long inDate)
    {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(inDate), _zoneId).format(_tzFormat);
    }

    /**
     * Format a date according to our stored formatter.
     * The passed time is expected to be close to the current time, so it is
     * compared to the last value passed and if it is within the same second,
     * the format is reused. Otherwise, a new cached format is created.
     *
     * @param now the milliseconds since unix epoch
     * @return Formatted date
     */
    @Deprecated
    public String formatNow(long now)
    {
        return formatTick(now)._string;
    }

    @Deprecated
    public String now()
    {
        return formatNow(System.currentTimeMillis());
    }

    @Deprecated
    public Tick tick()
    {
        return formatTick(System.currentTimeMillis());
    }

    protected Tick formatTick(long inDate)
    {
        long seconds = inDate / 1000;

        Tick tick = _tick;
        // recheck the tick, to save multiple formats
        if (tick == null || tick._seconds != seconds)
        {
            String s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inDate), _zoneId).format(_tzFormat);
            _tick = new Tick(seconds, s);
            tick = _tick;
        }
        return tick;
    }

    public String getFormatString()
    {
        return _formatString;
    }
}
