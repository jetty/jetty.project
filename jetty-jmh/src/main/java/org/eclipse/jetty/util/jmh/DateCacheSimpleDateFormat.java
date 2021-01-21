//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.jmh;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Date Format Cache.
 * Computes String representations of Dates and caches
 * the results so that subsequent requests within the same second
 * will be fast.
 *
 * Only format strings that contain either "ss".  Sub second formatting is
 * not handled.
 *
 * The timezone of the date may be included as an ID with the "zzz"
 * format string or as an offset with the "ZZZ" format string.
 *
 * If consecutive calls are frequently very different, then this
 * may be a little slower than a normal DateFormat.
 */
public class DateCacheSimpleDateFormat
{

    public static final String DEFAULT_FORMAT = "EEE MMM dd HH:mm:ss zzz yyyy";

    private final String _formatString;
    private final String _tzFormatString;
    private final SimpleDateFormat _tzFormat;
    private final Locale _locale;

    private volatile Tick _tick;

    public static class Tick
    {
        final long _seconds;
        final String _string;

        public Tick(long seconds, String string)
        {
            _seconds = seconds;
            _string = string;
        }
    }

    /**
     * Constructor.
     * Make a DateCache that will use a default format. The default format
     * generates the same results as Date.toString().
     */
    public DateCacheSimpleDateFormat()
    {
        this(DEFAULT_FORMAT);
    }

    /**
     * Constructor.
     * Make a DateCache that will use the given format
     *
     * @param format the format to use
     */
    public DateCacheSimpleDateFormat(String format)
    {
        this(format, null, TimeZone.getDefault());
    }

    public DateCacheSimpleDateFormat(String format, Locale l)
    {
        this(format, l, TimeZone.getDefault());
    }

    public DateCacheSimpleDateFormat(String format, Locale l, String tz)
    {
        this(format, l, TimeZone.getTimeZone(tz));
    }

    public DateCacheSimpleDateFormat(String format, Locale l, TimeZone tz)
    {
        _formatString = format;
        _locale = l;

        int zIndex = _formatString.indexOf("ZZZ");
        if (zIndex >= 0)
        {
            String ss1 = _formatString.substring(0, zIndex);
            String ss2 = _formatString.substring(zIndex + 3);
            int tzOffset = tz.getRawOffset();

            StringBuilder sb = new StringBuilder(_formatString.length() + 10);
            sb.append(ss1);
            sb.append("'");
            if (tzOffset >= 0)
                sb.append('+');
            else
            {
                tzOffset = -tzOffset;
                sb.append('-');
            }

            int raw = tzOffset / (1000 * 60);             // Convert to seconds
            int hr = raw / 60;
            int min = raw % 60;

            if (hr < 10)
                sb.append('0');
            sb.append(hr);
            if (min < 10)
                sb.append('0');
            sb.append(min);
            sb.append('\'');

            sb.append(ss2);
            _tzFormatString = sb.toString();
        }
        else
            _tzFormatString = _formatString;

        if (_locale != null)
        {
            _tzFormat = new SimpleDateFormat(_tzFormatString, _locale);
        }
        else
        {
            _tzFormat = new SimpleDateFormat(_tzFormatString);
        }
        _tzFormat.setTimeZone(tz);

        _tick = null;
    }

    public TimeZone getTimeZone()
    {
        return _tzFormat.getTimeZone();
    }

    /**
     * Format a date according to our stored formatter.
     *
     * @param inDate the Date
     * @return Formatted date
     */
    public String format(Date inDate)
    {
        long seconds = inDate.getTime() / 1000;

        Tick tick = _tick;

        // Is this the cached time
        if (tick == null || seconds != tick._seconds)
        {
            // It's a cache miss
            synchronized (this)
            {
                return _tzFormat.format(inDate);
            }
        }

        return tick._string;
    }

    /**
     * Format a date according to our stored formatter.
     * If it happens to be in the same second as the last formatNow
     * call, then the format is reused.
     *
     * @param inDate the date in milliseconds since unix epoch
     * @return Formatted date
     */
    public String format(long inDate)
    {
        long seconds = inDate / 1000;

        Tick tick = _tick;

        // Is this the cached time
        if (tick == null || seconds != tick._seconds)
        {
            // It's a cache miss
            Date d = new Date(inDate);
            synchronized (this)
            {
                return _tzFormat.format(d);
            }
        }

        return tick._string;
    }

    /**
     * Format a date according to our stored formatter.
     * The passed time is expected to be close to the current time, so it is
     * compared to the last value passed and if it is within the same second,
     * the format is reused.  Otherwise a new cached format is created.
     *
     * @param now the milliseconds since unix epoch
     * @return Formatted date
     */
    public String formatNow(long now)
    {
        long seconds = now / 1000;

        Tick tick = _tick;

        // Is this the cached time
        if (tick != null && tick._seconds == seconds)
            return tick._string;
        return formatTick(now)._string;
    }

    public String now()
    {
        return formatNow(System.currentTimeMillis());
    }

    public Tick tick()
    {
        return formatTick(System.currentTimeMillis());
    }

    protected Tick formatTick(long now)
    {
        long seconds = now / 1000;

        // Synchronize to protect _tzFormat
        synchronized (this)
        {
            // recheck the tick, to save multiple formats
            if (_tick == null || _tick._seconds != seconds)
            {
                String s = _tzFormat.format(new Date(now));
                return _tick = new Tick(seconds, s);
            }
            return _tick;
        }
    }

    public String getFormatString()
    {
        return _formatString;
    }
}
