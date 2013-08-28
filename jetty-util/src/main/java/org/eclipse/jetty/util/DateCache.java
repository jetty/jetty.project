//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

/* ------------------------------------------------------------ */
/**  Date Format Cache.
 * Computes String representations of Dates and caches
 * the results so that subsequent requests within the same minute
 * will be fast.
 *
 * Only format strings that contain either "ss" or "ss.SSS" are
 * handled.
 *
 * The timezone of the date may be included as an ID with the "zzz"
 * format string or as an offset with the "ZZZ" format string.
 *
 * If consecutive calls are frequently very different, then this
 * may be a little slower than a normal DateFormat.
 *
 */

public class DateCache
{
    public static final String DEFAULT_FORMAT="EEE MMM dd HH:mm:ss zzz yyyy";
    
    private final String _formatString;
    private String _tzFormatString;
    private SimpleDateFormat _tzFormat;
    
    private volatile Tick _tick;

    private Locale _locale	= null;
    private DateFormatSymbols	_dfs	= null;
    
    private static Timer __timer;
    

    public static Timer getTimer()
    {
        synchronized (DateCache.class)
        {
            if (__timer==null)
                __timer=new Timer("DateCache",true);
            return __timer;
        }
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class Tick
    {
        final long _seconds;
        final String _string;
        public Tick(long seconds, String string)
        {
            _seconds = seconds;
            _string = string;
        }
    }

    /* ------------------------------------------------------------ */
    /** Constructor.
     * Make a DateCache that will use a default format. The default format
     * generates the same results as Date.toString().
     */
    public DateCache()
    {
        this(DEFAULT_FORMAT);
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     * Make a DateCache that will use the given format
     */
    public DateCache(String format)
    {
        _formatString=format;
        setTimeZone(TimeZone.getDefault());
        
        synchronized (DateCache.class)
        {
            long now=System.currentTimeMillis();
            long tick=1000*((now/1000)+1)-now;
            formatNow();
            getTimer().scheduleAtFixedRate(new TimerTask()
            {
                @Override
                public void run()
                {
                    formatNow();
                }
            },
            tick,
            1000);
        }
    }
    
    /* ------------------------------------------------------------ */
    public DateCache(String format,Locale l)
    {
        this(format);
        _locale = l;
        setTimeZone(TimeZone.getDefault());       
    }
    
    /* ------------------------------------------------------------ */
    public DateCache(String format,DateFormatSymbols s)
    {
        this(format);
        _dfs = s;
        setTimeZone(TimeZone.getDefault());
    }

    /* ------------------------------------------------------------ */
    /** Set the timezone.
     * @param tz TimeZone
     */
    public void setTimeZone(TimeZone tz)
    {
        setTzFormatString(tz);        
        if( _locale != null ) 
        {
            _tzFormat=new SimpleDateFormat(_tzFormatString,_locale);
        }
        else if( _dfs != null ) 
        {
            _tzFormat=new SimpleDateFormat(_tzFormatString,_dfs);
        }
        else 
        {
            _tzFormat=new SimpleDateFormat(_tzFormatString);
        }
        _tzFormat.setTimeZone(tz);
        _tick=null;
    }

    /* ------------------------------------------------------------ */
    public TimeZone getTimeZone()
    {
        return _tzFormat.getTimeZone();
    }
    
    /* ------------------------------------------------------------ */
    /** Set the timezone.
     * @param timeZoneId TimeZoneId the ID of the zone as used by
     * TimeZone.getTimeZone(id)
     */
    public void setTimeZoneID(String timeZoneId)
    {
        setTimeZone(TimeZone.getTimeZone(timeZoneId));
    }
    
    /* ------------------------------------------------------------ */
    private void setTzFormatString(final  TimeZone tz )
    {
        int zIndex = _formatString.indexOf( "ZZZ" );
        if( zIndex >= 0 )
        {
            String ss1 = _formatString.substring( 0, zIndex );
            String ss2 = _formatString.substring( zIndex+3 );
            int tzOffset = tz.getRawOffset();
            
            StringBuilder sb = new StringBuilder(_formatString.length()+10);
            sb.append(ss1);
            sb.append("'");
            if( tzOffset >= 0 )
                sb.append( '+' );
            else
            {
                tzOffset = -tzOffset;
                sb.append( '-' );
            }
            
            int raw = tzOffset / (1000*60);		// Convert to seconds
            int hr = raw / 60;
            int min = raw % 60;
            
            if( hr < 10 )
                sb.append( '0' );
            sb.append( hr );
            if( min < 10 )
                sb.append( '0' );
            sb.append( min );
            sb.append( '\'' );
            
            sb.append(ss2);
            _tzFormatString=sb.toString();            
        }
        else
            _tzFormatString=_formatString;
        _tick=null;
    }


    /* ------------------------------------------------------------ */
    /** Format a date according to our stored formatter.
     * @param inDate 
     * @return Formatted date
     */
    public String format(Date inDate)
    {
        long seconds = inDate.getTime() / 1000;

        Tick tick=_tick;
        
        // Is this the cached time
        if (tick==null || seconds!=tick._seconds)
        {
            // It's a cache miss
            synchronized (this)
            {
                return _tzFormat.format(inDate);
            }
        }
        
        return tick._string;
    }
    
    /* ------------------------------------------------------------ */
    /** Format a date according to our stored formatter.
     * @param inDate 
     * @return Formatted date
     */
    public String format(long inDate)
    {
        long seconds = inDate / 1000;

        Tick tick=_tick;
        
        // Is this the cached time
        if (tick==null || seconds!=tick._seconds)
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
    
    /* ------------------------------------------------------------ */
    public String now()
    {
        return _tick._string;
    }
    
    /* ------------------------------------------------------------ */
    protected void formatNow()
    {
        long now = System.currentTimeMillis();
        long seconds = now / 1000;

        synchronized (this)
        {
            String s= _tzFormat.format(new Date(now));
            _tick=new Tick(seconds,s);
        }
    }

    /* ------------------------------------------------------------ */
    /** Format to string buffer. 
     * @param inDate Date the format
     * @param buffer StringBuilder
     */
    public void format(long inDate, StringBuilder buffer)
    {
        buffer.append(format(inDate));
    }

    /* ------------------------------------------------------------ */
    public String getFormatString()
    {
        return _formatString;
    }    

    /* ------------------------------------------------------------ */
    private volatile ByteBuffer _buffer;
    private volatile Object _last;
    public synchronized ByteBuffer formatBuffer(long date)
    {
        String d = format(date);
        if (d==_last)
            return _buffer;
        _last=d;
        _buffer=BufferUtil.toBuffer(d);
        
        return _buffer;
    }
}
