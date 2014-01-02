//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.BufferDateCache;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * HTTP Fields. A collection of HTTP header and or Trailer fields. 
 * 
 * <p>This class is not synchronized as it is expected that modifications will only be performed by a
 * single thread.
 * 
 * 
 */
public class HttpFields
{
    private static final Logger LOG = Log.getLogger(HttpFields.class);
    
    /* ------------------------------------------------------------ */
    public static final String __COOKIE_DELIM="\"\\\n\r\t\f\b%+ ;=";
    public static final TimeZone __GMT = TimeZone.getTimeZone("GMT");
    public static final BufferDateCache __dateCache = new BufferDateCache("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /* -------------------------------------------------------------- */
    static
    {
        __GMT.setID("GMT");
        __dateCache.setTimeZone(__GMT);
    }
    
    /* ------------------------------------------------------------ */
    public final static String __separators = ", \t";

    /* ------------------------------------------------------------ */
    private static final String[] DAYS =
    { "Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS =
    { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec", "Jan"};

    
    /* ------------------------------------------------------------ */
    private static class DateGenerator
    {
        private final StringBuilder buf = new StringBuilder(32);
        private final GregorianCalendar gc = new GregorianCalendar(__GMT);
        
        /**
         * Format HTTP date "EEE, dd MMM yyyy HH:mm:ss 'GMT'" 
         */
        public String formatDate(long date)
        {
            buf.setLength(0);
            gc.setTimeInMillis(date);
            
            int day_of_week = gc.get(Calendar.DAY_OF_WEEK);
            int day_of_month = gc.get(Calendar.DAY_OF_MONTH);
            int month = gc.get(Calendar.MONTH);
            int year = gc.get(Calendar.YEAR);
            int century = year / 100;
            year = year % 100;
            
            int hours = gc.get(Calendar.HOUR_OF_DAY);
            int minutes = gc.get(Calendar.MINUTE);
            int seconds = gc.get(Calendar.SECOND);

            buf.append(DAYS[day_of_week]);
            buf.append(',');
            buf.append(' ');
            StringUtil.append2digits(buf, day_of_month);

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

        /* ------------------------------------------------------------ */
        /**
         * Format "EEE, dd-MMM-yy HH:mm:ss 'GMT'" for cookies
         */
        public void formatCookieDate(StringBuilder buf, long date)
        {
            gc.setTimeInMillis(date);
            
            int day_of_week = gc.get(Calendar.DAY_OF_WEEK);
            int day_of_month = gc.get(Calendar.DAY_OF_MONTH);
            int month = gc.get(Calendar.MONTH);
            int year = gc.get(Calendar.YEAR);
            year = year % 10000;

            int epoch = (int) ((date / 1000) % (60 * 60 * 24));
            int seconds = epoch % 60;
            epoch = epoch / 60;
            int minutes = epoch % 60;
            int hours = epoch / 60;

            buf.append(DAYS[day_of_week]);
            buf.append(',');
            buf.append(' ');
            StringUtil.append2digits(buf, day_of_month);

            buf.append('-');
            buf.append(MONTHS[month]);
            buf.append('-');
            StringUtil.append2digits(buf, year/100);
            StringUtil.append2digits(buf, year%100);
            
            buf.append(' ');
            StringUtil.append2digits(buf, hours);
            buf.append(':');
            StringUtil.append2digits(buf, minutes);
            buf.append(':');
            StringUtil.append2digits(buf, seconds);
            buf.append(" GMT");
        }
    }

    /* ------------------------------------------------------------ */
    private static final ThreadLocal<DateGenerator> __dateGenerator =new ThreadLocal<DateGenerator>()
    {
        @Override
        protected DateGenerator initialValue()
        {
            return new DateGenerator();
        }
    };
    
    /* ------------------------------------------------------------ */
    /**
     * Format HTTP date "EEE, dd MMM yyyy HH:mm:ss 'GMT'" 
     */
    public static String formatDate(long date)
    {
        return __dateGenerator.get().formatDate(date);
    }

    /* ------------------------------------------------------------ */
    /**
     * Format "EEE, dd-MMM-yyyy HH:mm:ss 'GMT'" for cookies
     */
    public static void formatCookieDate(StringBuilder buf, long date)
    {
        __dateGenerator.get().formatCookieDate(buf,date);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Format "EEE, dd-MMM-yyyy HH:mm:ss 'GMT'" for cookies
     */
    public static String formatCookieDate(long date)
    {
        StringBuilder buf = new StringBuilder(28);
        formatCookieDate(buf, date);
        return buf.toString();
    }

    /* ------------------------------------------------------------ */
    private final static String __dateReceiveFmt[] =
    {   
        "EEE, dd MMM yyyy HH:mm:ss zzz", 
        "EEE, dd-MMM-yy HH:mm:ss",
        "EEE MMM dd HH:mm:ss yyyy",

        "EEE, dd MMM yyyy HH:mm:ss", "EEE dd MMM yyyy HH:mm:ss zzz", 
        "EEE dd MMM yyyy HH:mm:ss", "EEE MMM dd yyyy HH:mm:ss zzz", "EEE MMM dd yyyy HH:mm:ss", 
        "EEE MMM-dd-yyyy HH:mm:ss zzz", "EEE MMM-dd-yyyy HH:mm:ss", "dd MMM yyyy HH:mm:ss zzz", 
        "dd MMM yyyy HH:mm:ss", "dd-MMM-yy HH:mm:ss zzz", "dd-MMM-yy HH:mm:ss", "MMM dd HH:mm:ss yyyy zzz", 
        "MMM dd HH:mm:ss yyyy", "EEE MMM dd HH:mm:ss yyyy zzz",  
        "EEE, MMM dd HH:mm:ss yyyy zzz", "EEE, MMM dd HH:mm:ss yyyy", "EEE, dd-MMM-yy HH:mm:ss zzz", 
        "EEE dd-MMM-yy HH:mm:ss zzz", "EEE dd-MMM-yy HH:mm:ss",
    };

    /* ------------------------------------------------------------ */
    private static class DateParser
    {
        final SimpleDateFormat _dateReceive[]= new SimpleDateFormat[__dateReceiveFmt.length];
 
        long parse(final String dateVal)
        {
            for (int i = 0; i < _dateReceive.length; i++)
            {
                if (_dateReceive[i] == null)
                {
                    _dateReceive[i] = new SimpleDateFormat(__dateReceiveFmt[i], Locale.US);
                    _dateReceive[i].setTimeZone(__GMT);
                }

                try
                {
                    Date date = (Date) _dateReceive[i].parseObject(dateVal);
                    return date.getTime();
                }
                catch (java.lang.Exception e)
                {
                    // LOG.ignore(e);
                }
            }
            
            if (dateVal.endsWith(" GMT"))
            {
                final String val = dateVal.substring(0, dateVal.length() - 4);

                for (int i = 0; i < _dateReceive.length; i++)
                {
                    try
                    {
                        Date date = (Date) _dateReceive[i].parseObject(val);
                        return date.getTime();
                    }
                    catch (java.lang.Exception e)
                    {
                        // LOG.ignore(e);
                    }
                }
            }    
            return -1;
        }
    }

    /* ------------------------------------------------------------ */
    public static long parseDate(String date)
    {
        return __dateParser.get().parse(date);
    }

    /* ------------------------------------------------------------ */
    private static final ThreadLocal<DateParser> __dateParser =new ThreadLocal<DateParser>()
    {
        @Override
        protected DateParser initialValue()
        {
            return new DateParser();
        }
    };

    /* -------------------------------------------------------------- */
    public final static String __01Jan1970=formatDate(0);
    public final static Buffer __01Jan1970_BUFFER=new ByteArrayBuffer(__01Jan1970);
    public final static String __01Jan1970_COOKIE = formatCookieDate(0).trim();

    /* -------------------------------------------------------------- */
    private final ArrayList<Field> _fields = new ArrayList<Field>(20);
    private final HashMap<Buffer,Field> _names = new HashMap<Buffer,Field>(32);
    
    /* ------------------------------------------------------------ */
    /**
     * Constructor.
     */
    public HttpFields()
    {
    }

    // TODO externalize this cache so it can be configurable
    private static ConcurrentMap<String, Buffer> __cache = new ConcurrentHashMap<String, Buffer>();
    private static int __cacheSize = Integer.getInteger("org.eclipse.jetty.http.HttpFields.CACHE",2000);
    
    /* -------------------------------------------------------------- */
    private Buffer convertValue(String value)
    {
        Buffer buffer = __cache.get(value);
        if (buffer!=null)
            return buffer;
        
        try
        {   
            buffer = new ByteArrayBuffer(value,StringUtil.__ISO_8859_1);
            
            if (__cacheSize>0)
            {
                if (__cache.size()>__cacheSize)
                    __cache.clear();
                Buffer b=__cache.putIfAbsent(value,buffer);
                if (b!=null)
                    buffer=b;
            }
            
            return buffer;
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* -------------------------------------------------------------- */
    /**
     * Get Collection of header names. 
     */
    public Collection<String> getFieldNamesCollection()
    {
        final List<String> list = new ArrayList<String>(_fields.size());

	for (Field f : _fields)
	{
	    if (f!=null)
	        list.add(BufferUtil.to8859_1_String(f._name));
	}
	return list;
    }
    
    /* -------------------------------------------------------------- */
    /**
     * Get enumeration of header _names. Returns an enumeration of strings representing the header
     * _names for this request.
     */
    public Enumeration<String> getFieldNames()
    {
        final Enumeration<?> buffers = Collections.enumeration(_names.keySet());
        return new Enumeration<String>()
        {
            public String nextElement()
            {
                return buffers.nextElement().toString();
            }
            
            public boolean hasMoreElements()
            {
                return buffers.hasMoreElements();
            }
        }; 
    }
    
    /* ------------------------------------------------------------ */
    public int size()
    {
        return _fields.size();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get a Field by index.
     * @return A Field value or null if the Field value has not been set
     * 
     */
    public Field getField(int i)
    {
        return _fields.get(i);
    }

    /* ------------------------------------------------------------ */
    private Field getField(String name)
    {
        return _names.get(HttpHeaders.CACHE.lookup(name));
    }

    /* ------------------------------------------------------------ */
    private Field getField(Buffer name)
    {
        return _names.get(HttpHeaders.CACHE.lookup(name));
    }

    /* ------------------------------------------------------------ */
    public boolean containsKey(Buffer name)
    {
        return _names.containsKey(HttpHeaders.CACHE.lookup(name));
    }

    /* ------------------------------------------------------------ */
    public boolean containsKey(String name)
    {
        return _names.containsKey(HttpHeaders.CACHE.lookup(name));
    }

    /* -------------------------------------------------------------- */
    /**
     * @return the value of a field, or null if not found. For multiple fields of the same name,
     *         only the first is returned.
     * @param name the case-insensitive field name
     */
    public String getStringField(String name)
    {
        Field field = getField(name);
        return field==null?null:field.getValue();
    }

    /* -------------------------------------------------------------- */
    /**
     * @return the value of a field, or null if not found. For multiple fields of the same name,
     *         only the first is returned.
     * @param name the case-insensitive field name
     */
    public String getStringField(Buffer name)
    {
        Field field = getField(name);
        return field==null?null:field.getValue();
    }

    /* -------------------------------------------------------------- */
    /**
     * @return the value of a field, or null if not found. For multiple fields of the same name,
     *         only the first is returned.
     * @param name the case-insensitive field name
     */
    public Buffer get(Buffer name)
    {
        Field field = getField(name);
        return field==null?null:field._value;
    }


    /* -------------------------------------------------------------- */
    /**
     * Get multi headers
     * 
     * @return Enumeration of the values, or null if no such header.
     * @param name the case-insensitive field name
     */
    public Collection<String> getValuesCollection(String name)
    {
        Field field = getField(name);
	if (field==null)
	    return null;

        final List<String> list = new ArrayList<String>();

	while(field!=null)
	{
	    list.add(field.getValue());
	    field=field._next;
	}
	return list;
    }

    /* -------------------------------------------------------------- */
    /**
     * Get multi headers
     * 
     * @return Enumeration of the values
     * @param name the case-insensitive field name
     */
    public Enumeration<String> getValues(String name)
    {
        final Field field = getField(name);
        if (field == null) 
        {
            List<String> empty=Collections.emptyList();
            return Collections.enumeration(empty);
        }

        return new Enumeration<String>()
        {
            Field f = field;

            public boolean hasMoreElements()
            {
                return f != null;
            }

            public String nextElement() throws NoSuchElementException
            {
                if (f == null) throw new NoSuchElementException();
                Field n = f;
                f = f._next;
                return n.getValue();
            }
        };
    }

    /* -------------------------------------------------------------- */
    /**
     * Get multi headers
     * 
     * @return Enumeration of the value Strings
     * @param name the case-insensitive field name
     */
    public Enumeration<String> getValues(Buffer name)
    {
        final Field field = getField(name);
        if (field == null) 
        {
            List<String> empty=Collections.emptyList();
            return Collections.enumeration(empty);
        }

        return new Enumeration<String>()
        {
            Field f = field;

            public boolean hasMoreElements()
            {
                return f != null;
            }

            public String nextElement() throws NoSuchElementException
            {
                if (f == null) throw new NoSuchElementException();
                Field n = f;
                f = f._next;
                return n.getValue();
            }
        };
    }

    /* -------------------------------------------------------------- */
    /**
     * Get multi field values with separator. The multiple values can be represented as separate
     * headers of the same name, or by a single header using the separator(s), or a combination of
     * both. Separators may be quoted.
     * 
     * @param name the case-insensitive field name
     * @param separators String of separators.
     * @return Enumeration of the values, or null if no such header.
     */
    public Enumeration<String> getValues(String name, final String separators)
    {
        final Enumeration<String> e = getValues(name);
        if (e == null) 
            return null;
        return new Enumeration<String>()
        {
            QuotedStringTokenizer tok = null;

            public boolean hasMoreElements()
            {
                if (tok != null && tok.hasMoreElements()) return true;
                while (e.hasMoreElements())
                {
                    String value = e.nextElement();
                    tok = new QuotedStringTokenizer(value, separators, false, false);
                    if (tok.hasMoreElements()) return true;
                }
                tok = null;
                return false;
            }

            public String nextElement() throws NoSuchElementException
            {
                if (!hasMoreElements()) throw new NoSuchElementException();
                String next = (String) tok.nextElement();
                if (next != null) next = next.trim();
                return next;
            }
        };
    }

    
    /* -------------------------------------------------------------- */
    /**
     * Set a field.
     * 
     * @param name the name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(String name, String value)
    {
        if (value==null)
            remove(name);
        else
        {
            Buffer n = HttpHeaders.CACHE.lookup(name);
            Buffer v = convertValue(value);
            put(n, v);
        }
    }

    /* -------------------------------------------------------------- */
    /**
     * Set a field.
     * 
     * @param name the name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(Buffer name, String value)
    {
        Buffer n = HttpHeaders.CACHE.lookup(name);
        Buffer v = convertValue(value);
        put(n, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Set a field.
     * 
     * @param name the name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(Buffer name, Buffer value)
    {
        remove(name);
        if (value == null)
            return;

        if (!(name instanceof BufferCache.CachedBuffer)) 
            name = HttpHeaders.CACHE.lookup(name);
        if (!(value instanceof CachedBuffer))
            value= HttpHeaderValues.CACHE.lookup(value).asImmutableBuffer();
        
        // new value;
        Field field = new Field(name, value);
        _fields.add(field);
        _names.put(name, field);
    }

    /* -------------------------------------------------------------- */
    /**
     * Set a field.
     * 
     * @param name the name of the field
     * @param list the List value of the field. If null the field is cleared.
     */
    public void put(String name, List<?> list)
    {
        if (list == null || list.size() == 0)
        {
            remove(name);
            return;
        }
        Buffer n = HttpHeaders.CACHE.lookup(name);

        Object v = list.get(0);
        if (v != null)
            put(n, HttpHeaderValues.CACHE.lookup(v.toString()));
        else
            remove(n);

        if (list.size() > 1)
        {
            java.util.Iterator<?> iter = list.iterator();
            iter.next();
            while (iter.hasNext())
            {
                v = iter.next();
                if (v != null) put(n, HttpHeaderValues.CACHE.lookup(v.toString()));
            }
        }
    }

    /* -------------------------------------------------------------- */
    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     * 
     * @param name the name of the field
     * @param value the value of the field.
     * @exception IllegalArgumentException If the name is a single valued field and already has a
     *                value.
     */
    public void add(String name, String value) throws IllegalArgumentException
    {
        if (value==null)
            return;
        Buffer n = HttpHeaders.CACHE.lookup(name);
        Buffer v = convertValue(value);
        add(n, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     * 
     * @param name the name of the field
     * @param value the value of the field.
     * @exception IllegalArgumentException If the name is a single valued field and already has a
     *                value.
     */
    public void add(Buffer name, Buffer value) throws IllegalArgumentException
    {   
        if (value == null) throw new IllegalArgumentException("null value");

        if (!(name instanceof CachedBuffer))
            name = HttpHeaders.CACHE.lookup(name);
        name=name.asImmutableBuffer();
        
        if (!(value instanceof CachedBuffer) && HttpHeaderValues.hasKnownValues(HttpHeaders.CACHE.getOrdinal(name)))
            value= HttpHeaderValues.CACHE.lookup(value);
        value=value.asImmutableBuffer();
        
        Field field = _names.get(name);
        Field last = null;
        while (field != null)
        {
            last = field;
            field = field._next;
        }

        // create the field
        field = new Field(name, value);
        _fields.add(field);

        // look for chain to add too
        if (last != null)
            last._next = field;
        else
            _names.put(name, field);
    }

    /* ------------------------------------------------------------ */
    /**
     * Remove a field.
     * 
     * @param name
     */
    public void remove(String name)
    {
        remove(HttpHeaders.CACHE.lookup(name));
    }

    /* ------------------------------------------------------------ */
    /**
     * Remove a field.
     * 
     * @param name
     */
    public void remove(Buffer name)
    {
        if (!(name instanceof BufferCache.CachedBuffer)) 
            name = HttpHeaders.CACHE.lookup(name);
        Field field = _names.remove(name);
        while (field != null)
        {
            _fields.remove(field);
            field = field._next;
        }
    }

    /* -------------------------------------------------------------- */
    /**
     * Get a header as an long value. Returns the value of an integer field or -1 if not found. The
     * case of the field name is ignored.
     * 
     * @param name the case-insensitive field name
     * @exception NumberFormatException If bad long found
     */
    public long getLongField(String name) throws NumberFormatException
    {
        Field field = getField(name);
        return field==null?-1L:field.getLongValue();
    }

    /* -------------------------------------------------------------- */
    /**
     * Get a header as an long value. Returns the value of an integer field or -1 if not found. The
     * case of the field name is ignored.
     * 
     * @param name the case-insensitive field name
     * @exception NumberFormatException If bad long found
     */
    public long getLongField(Buffer name) throws NumberFormatException
    {
        Field field = getField(name);
        return field==null?-1L:field.getLongValue();
    }

    /* -------------------------------------------------------------- */
    /**
     * Get a header as a date value. Returns the value of a date field, or -1 if not found. The case
     * of the field name is ignored.
     * 
     * @param name the case-insensitive field name
     */
    public long getDateField(String name)
    {
        Field field = getField(name);
        if (field == null) 
            return -1;

        String val = valueParameters(BufferUtil.to8859_1_String(field._value), null);
        if (val == null) 
            return -1;

        final long date = __dateParser.get().parse(val);
        if (date==-1)
            throw new IllegalArgumentException("Cannot convert date: " + val);
        return date;
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of an long field.
     * 
     * @param name the field name
     * @param value the field long value
     */
    public void putLongField(Buffer name, long value)
    {
        Buffer v = BufferUtil.toBuffer(value);
        put(name, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of an long field.
     * 
     * @param name the field name
     * @param value the field long value
     */
    public void putLongField(String name, long value)
    {
        Buffer n = HttpHeaders.CACHE.lookup(name);
        Buffer v = BufferUtil.toBuffer(value);
        put(n, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of an long field.
     * 
     * @param name the field name
     * @param value the field long value
     */
    public void addLongField(String name, long value)
    {
        Buffer n = HttpHeaders.CACHE.lookup(name);
        Buffer v = BufferUtil.toBuffer(value);
        add(n, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of an long field.
     * 
     * @param name the field name
     * @param value the field long value
     */
    public void addLongField(Buffer name, long value)
    {
        Buffer v = BufferUtil.toBuffer(value);
        add(name, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of a date field.
     * 
     * @param name the field name
     * @param date the field date value
     */
    public void putDateField(Buffer name, long date)
    {
        String d=formatDate(date);
        Buffer v = new ByteArrayBuffer(d);
        put(name, v);
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of a date field.
     * 
     * @param name the field name
     * @param date the field date value
     */
    public void putDateField(String name, long date)
    {
        Buffer n = HttpHeaders.CACHE.lookup(name);
        putDateField(n,date);
    }

    /* -------------------------------------------------------------- */
    /**
     * Sets the value of a date field.
     * 
     * @param name the field name
     * @param date the field date value
     */
    public void addDateField(String name, long date)
    {
        String d=formatDate(date);
        Buffer n = HttpHeaders.CACHE.lookup(name);
        Buffer v = new ByteArrayBuffer(d);
        add(n, v);
    }

    /* ------------------------------------------------------------ */
    /**
     * Format a set cookie value
     * 
     * @param cookie The cookie.
     */
    public void addSetCookie(HttpCookie cookie)
    {
        addSetCookie(
                cookie.getName(),
                cookie.getValue(),
                cookie.getDomain(),
                cookie.getPath(),
                cookie.getMaxAge(),
                cookie.getComment(),
                cookie.isSecure(),
                cookie.isHttpOnly(),
                cookie.getVersion());
    }

    /**
     * Format a set cookie value
     * 
     * @param name the name
     * @param value the value
     * @param domain the domain
     * @param path the path
     * @param maxAge the maximum age
     * @param comment the comment (only present on versions > 0)
     * @param isSecure true if secure cookie
     * @param isHttpOnly true if for http only
     * @param version version of cookie logic to use (0 == default behavior)
     */
    public void addSetCookie(
            final String name, 
            final String value, 
            final String domain,
            final String path, 
            final long maxAge,
            final String comment, 
            final boolean isSecure,
            final boolean isHttpOnly, 
            int version)
    {
    	String delim=__COOKIE_DELIM;
    	
        // Check arguments
        if (name == null || name.length() == 0) 
            throw new IllegalArgumentException("Bad cookie name");

        // Format value and params
        StringBuilder buf = new StringBuilder(128);
        String name_value_params;
        QuotedStringTokenizer.quoteIfNeeded(buf, name, delim);
        buf.append('=');
        String start=buf.toString();
        boolean hasDomain = false;
        boolean hasPath = false;
        
        if (value != null && value.length() > 0)
            QuotedStringTokenizer.quoteIfNeeded(buf, value, delim);        

        if (comment != null && comment.length() > 0)
        {
            buf.append(";Comment=");
            QuotedStringTokenizer.quoteIfNeeded(buf, comment, delim);
        }

        if (path != null && path.length() > 0)
        {
            hasPath = true;
            buf.append(";Path=");
            if (path.trim().startsWith("\""))
                buf.append(path);
            else
                QuotedStringTokenizer.quoteIfNeeded(buf,path,delim);
        }
        if (domain != null && domain.length() > 0)
        {
            hasDomain = true;
            buf.append(";Domain=");
            QuotedStringTokenizer.quoteIfNeeded(buf,domain.toLowerCase(Locale.ENGLISH),delim);
        }

        if (maxAge >= 0)
        {
            // Always add the expires param as some browsers still don't handle max-age
            buf.append(";Expires=");
            if (maxAge == 0)
                buf.append(__01Jan1970_COOKIE);
            else
                formatCookieDate(buf, System.currentTimeMillis() + 1000L * maxAge);

            if (version >0)
            {
                buf.append(";Max-Age=");
                buf.append(maxAge);
            }
        }

        if (isSecure)
            buf.append(";Secure");
        if (isHttpOnly) 
            buf.append(";HttpOnly");

        name_value_params = buf.toString();
        
        // remove existing set-cookie of same name
        Field field = getField(HttpHeaders.SET_COOKIE);
        Field last=null;
        while (field!=null)
        {
            String val = (field._value == null ? null : field._value.toString());
            if (val!=null && val.startsWith(start))
            {
                //existing cookie has same name, does it also match domain and path?
                if (((!hasDomain && !val.contains("Domain")) || (hasDomain && val.contains("Domain="+domain))) &&
                    ((!hasPath && !val.contains("Path")) || (hasPath && val.contains("Path="+path))))
                {
                    _fields.remove(field);
                    if (last==null)
                        _names.put(HttpHeaders.SET_COOKIE_BUFFER,field._next);
                    else
                        last._next=field._next;
                    break;
                }
            }
            last=field;
            field=field._next;
        }

        add(HttpHeaders.SET_COOKIE_BUFFER, new ByteArrayBuffer(name_value_params));
        
        // Expire responses with set-cookie headers so they do not get cached.
        put(HttpHeaders.EXPIRES_BUFFER, __01Jan1970_BUFFER);
    }

    /* -------------------------------------------------------------- */
    public void putTo(Buffer buffer) throws IOException
    {
        for (int i = 0; i < _fields.size(); i++)
        {
            Field field = _fields.get(i);
            if (field != null) 
                field.putTo(buffer);
        }
        BufferUtil.putCRLF(buffer);
    }

    /* -------------------------------------------------------------- */
    public String toString()
    {
        try
        {
            StringBuffer buffer = new StringBuffer();
            for (int i = 0; i < _fields.size(); i++)
            {
                Field field = (Field) _fields.get(i);
                if (field != null)
                {
                    String tmp = field.getName();
                    if (tmp != null) buffer.append(tmp);
                    buffer.append(": ");
                    tmp = field.getValue();
                    if (tmp != null) buffer.append(tmp);
                    buffer.append("\r\n");
                }
            }
            buffer.append("\r\n");
            return buffer.toString();
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return e.toString();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Clear the header.
     */
    public void clear()
    {
        _fields.clear();
        _names.clear();
    }

    /* ------------------------------------------------------------ */
    /**
     * Add fields from another HttpFields instance. Single valued fields are replaced, while all
     * others are added.
     * 
     * @param fields
     */
    public void add(HttpFields fields)
    {
        if (fields == null) return;

        Enumeration e = fields.getFieldNames();
        while (e.hasMoreElements())
        {
            String name = (String) e.nextElement();
            Enumeration values = fields.getValues(name);
            while (values.hasMoreElements())
                add(name, (String) values.nextElement());
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get field value parameters. Some field values can have parameters. This method separates the
     * value from the parameters and optionally populates a map with the parameters. For example:
     * 
     * <PRE>
     * 
     * FieldName : Value ; param1=val1 ; param2=val2
     * 
     * </PRE>
     * 
     * @param value The Field value, possibly with parameteres.
     * @param parameters A map to populate with the parameters, or null
     * @return The value.
     */
    public static String valueParameters(String value, Map<String,String> parameters)
    {
        if (value == null) return null;

        int i = value.indexOf(';');
        if (i < 0) return value;
        if (parameters == null) return value.substring(0, i).trim();

        StringTokenizer tok1 = new QuotedStringTokenizer(value.substring(i), ";", false, true);
        while (tok1.hasMoreTokens())
        {
            String token = tok1.nextToken();
            StringTokenizer tok2 = new QuotedStringTokenizer(token, "= ");
            if (tok2.hasMoreTokens())
            {
                String paramName = tok2.nextToken();
                String paramVal = null;
                if (tok2.hasMoreTokens()) paramVal = tok2.nextToken();
                parameters.put(paramName, paramVal);
            }
        }

        return value.substring(0, i).trim();
    }

    /* ------------------------------------------------------------ */
    private static final Float __one = new Float("1.0");
    private static final Float __zero = new Float("0.0");
    private static final StringMap __qualities = new StringMap();
    static
    {
        __qualities.put(null, __one);
        __qualities.put("1.0", __one);
        __qualities.put("1", __one);
        __qualities.put("0.9", new Float("0.9"));
        __qualities.put("0.8", new Float("0.8"));
        __qualities.put("0.7", new Float("0.7"));
        __qualities.put("0.66", new Float("0.66"));
        __qualities.put("0.6", new Float("0.6"));
        __qualities.put("0.5", new Float("0.5"));
        __qualities.put("0.4", new Float("0.4"));
        __qualities.put("0.33", new Float("0.33"));
        __qualities.put("0.3", new Float("0.3"));
        __qualities.put("0.2", new Float("0.2"));
        __qualities.put("0.1", new Float("0.1"));
        __qualities.put("0", __zero);
        __qualities.put("0.0", __zero);
    }

    /* ------------------------------------------------------------ */
    public static Float getQuality(String value)
    {
        if (value == null) return __zero;

        int qe = value.indexOf(";");
        if (qe++ < 0 || qe == value.length()) return __one;

        if (value.charAt(qe++) == 'q')
        {
            qe++;
            Map.Entry entry = __qualities.getEntry(value, qe, value.length() - qe);
            if (entry != null) return (Float) entry.getValue();
        }

        HashMap params = new HashMap(3);
        valueParameters(value, params);
        String qs = (String) params.get("q");
        Float q = (Float) __qualities.get(qs);
        if (q == null)
        {
            try
            {
                q = new Float(qs);
            }
            catch (Exception e)
            {
                q = __one;
            }
        }
        return q;
    }

    /* ------------------------------------------------------------ */
    /**
     * List values in quality order.
     * 
     * @param e Enumeration of values with quality parameters
     * @return values in quality order.
     */
    public static List qualityList(Enumeration e)
    {
        if (e == null || !e.hasMoreElements()) return Collections.EMPTY_LIST;

        Object list = null;
        Object qual = null;

        // Assume list will be well ordered and just add nonzero
        while (e.hasMoreElements())
        {
            String v = e.nextElement().toString();
            Float q = getQuality(v);

            if (q.floatValue() >= 0.001)
            {
                list = LazyList.add(list, v);
                qual = LazyList.add(qual, q);
            }
        }

        List vl = LazyList.getList(list, false);
        if (vl.size() < 2) return vl;

        List ql = LazyList.getList(qual, false);

        // sort list with swaps
        Float last = __zero;
        for (int i = vl.size(); i-- > 0;)
        {
            Float q = (Float) ql.get(i);
            if (last.compareTo(q) > 0)
            {
                Object tmp = vl.get(i);
                vl.set(i, vl.get(i + 1));
                vl.set(i + 1, tmp);
                ql.set(i, ql.get(i + 1));
                ql.set(i + 1, q);
                last = __zero;
                i = vl.size();
                continue;
            }
            last = q;
        }
        ql.clear();
        return vl;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static final class Field
    {
        private Buffer _name;
        private Buffer _value;
        private Field _next;

        /* ------------------------------------------------------------ */
        private Field(Buffer name, Buffer value)
        {
            _name = name;
            _value = value;
            _next = null;
        }
        
        /* ------------------------------------------------------------ */
        public void putTo(Buffer buffer) throws IOException
        {
            int o=(_name instanceof CachedBuffer)?((CachedBuffer)_name).getOrdinal():-1;
            if (o>=0)
                buffer.put(_name);
            else
            {
                int s=_name.getIndex();
                int e=_name.putIndex();
                while (s<e)
                {
                    byte b=_name.peek(s++);
                    switch(b)
                    {
                        case '\r':
                        case '\n':
                        case ':' :
                            continue;
                        default:
                            buffer.put(b);
                    }
                }
            }
            
            buffer.put((byte) ':');
            buffer.put((byte) ' ');
            
            o=(_value instanceof CachedBuffer)?((CachedBuffer)_value).getOrdinal():-1;
            if (o>=0)
                buffer.put(_value);
            else
            {
                int s=_value.getIndex();
                int e=_value.putIndex();
                while (s<e)
                {
                    byte b=_value.peek(s++);
                    switch(b)
                    {
                        case '\r':
                        case '\n':
                            continue;
                        default:
                            buffer.put(b);
                    }
                }
            }

            BufferUtil.putCRLF(buffer);
        }

        /* ------------------------------------------------------------ */
        public String getName()
        {
            return BufferUtil.to8859_1_String(_name);
        }

        /* ------------------------------------------------------------ */
        Buffer getNameBuffer()
        {
            return _name;
        }

        /* ------------------------------------------------------------ */
        public int getNameOrdinal()
        {
            return HttpHeaders.CACHE.getOrdinal(_name);
        }

        /* ------------------------------------------------------------ */
        public String getValue()
        {
            return BufferUtil.to8859_1_String(_value);
        }

        /* ------------------------------------------------------------ */
        public Buffer getValueBuffer()
        {
            return _value;
        }

        /* ------------------------------------------------------------ */
        public int getValueOrdinal()
        {
            return HttpHeaderValues.CACHE.getOrdinal(_value);
        }

        /* ------------------------------------------------------------ */
        public int getIntValue()
        {
            return (int) getLongValue();
        }

        /* ------------------------------------------------------------ */
        public long getLongValue()
        {
            return BufferUtil.toLong(_value);
        }

        /* ------------------------------------------------------------ */
        public String toString()
        {
            return ("[" + getName() + "=" + _value + (_next == null ? "" : "->") + "]");
        }
    }
}
