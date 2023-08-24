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
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Computes String representations of Dates then caches the results so
 * that subsequent requests within the same second will be fast.
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
    private final DateTimeFormatter _tzFormat1;
    private final DateTimeFormatter _tzFormat2;
    private final ZoneId _zoneId;

    private volatile TickHolder _tickHolder;

    private static class TickHolder
    {
        public TickHolder(Tick t1, Tick t2)
        {
            tick1 = t1;
            tick2 = t2;
        }

        final Tick tick1;
        final Tick tick2;
    }

    public static class Tick
    {
        private final long _seconds;
        private final String _prefix;
        private final String _suffix;

        public Tick(long seconds, String prefix, String suffix)
        {
            _seconds = seconds;
            _prefix = prefix;
            _suffix = suffix;
        }

        public long getSeconds()
        {
            return _seconds;
        }

        public String format(long inDate)
        {
            if (_suffix == null)
                return _prefix;

            long ms = inDate % 1000;
            StringBuilder sb = new StringBuilder();
            sb.append(_prefix);
            if (ms < 10)
                sb.append("00").append(ms);
            else if (ms < 100)
                sb.append('0').append(ms);
            else
                sb.append(ms);
            sb.append(_suffix);
            return sb.toString();
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
        this(format, l, tz, true);
    }

    public DateCache(String format, Locale l, TimeZone tz, boolean subSecondPrecision)
    {
        format = format.replaceFirst("S+", "SSS");
        _formatString = format;
        _zoneId = tz.toZoneId();

        String format1 = format;
        String format2 = null;
        boolean subSecond;
        if (subSecondPrecision)
        {
            int msIndex = format.indexOf("SSS");
            subSecond = (msIndex >= 0);
            if (subSecond)
            {
                format1 = format.substring(0, msIndex);
                format2 = format.substring(msIndex + 3);
            }
        }
        else
        {
            subSecond = false;
            format1 = format.replace("SSS", "000");
        }

        _tzFormat1 = createFormatter(format1, l, _zoneId);
        _tzFormat2 = subSecond ? createFormatter(format2, l, _zoneId) : null;
    }

    private DateTimeFormatter createFormatter(String format, Locale locale, ZoneId zoneId)
    {
        if (locale == null)
            return DateTimeFormatter.ofPattern(format).withZone(zoneId);
        else
            return DateTimeFormatter.ofPattern(format, locale).withZone(zoneId);
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
        return format(inDate.getTime());
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
        return formatTick(inDate).format(inDate);
    }

    /**
     * Format a date according to supplied formatter.
     *
     * @param inDate the date in milliseconds since unix epoch.
     * @return Formatted date.
     */
    protected String doFormat(long inDate, DateTimeFormatter formatter)
    {
        if (formatter == null)
            return null;
        return formatter.format(Instant.ofEpochMilli(inDate));
    }

    /**
     * Format a date according to our stored formatter.
     * The passed time is expected to be close to the current time, so it is
     * compared to the last value passed and if it is within the same second,
     * the format is reused. Otherwise, a new cached format is created.
     *
     * @param now the milliseconds since unix epoch
     * @return Formatted date
     * @deprecated use {@link #format(long)}
     */
    @Deprecated
    public String formatNow(long now)
    {
        return format(now);
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

        // Two Ticks are cached so that for monotonically increasing times to not see any jitter from multiple cores.
        // The ticks are kept in a volatile field, so there a small risk of inconsequential multiple recalculations
        TickHolder holder = _tickHolder;
        if (holder != null)
        {
            if (holder.tick1 != null && holder.tick1.getSeconds() == seconds)
                return holder.tick1;
            if (holder.tick2 != null && holder.tick2.getSeconds() == seconds)
                return holder.tick2;
        }

        String prefix = doFormat(inDate, _tzFormat1);
        String suffix = doFormat(inDate, _tzFormat2);
        Tick tick = new Tick(seconds, prefix, suffix);
        _tickHolder = new TickHolder(tick, (holder == null) ? null : holder.tick1);
        return tick;
    }

    public String getFormatString()
    {
        return _formatString;
    }
}
