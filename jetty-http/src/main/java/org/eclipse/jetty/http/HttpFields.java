//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * HTTP Fields. A collection of HTTP header and or Trailer fields.
 *
 * <p>This class is not synchronized as it is expected that modifications will only be performed by a
 * single thread.
 * 
 * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
 *
 */
public class HttpFields implements Iterable<HttpField>
{
    @Deprecated
    public static final String __separators = ", \t";

    private static final Logger LOG = Log.getLogger(HttpFields.class);

    private HttpField[] _fields;
    private int _size;
    
    /**
     * Initialize an empty HttpFields.
     */
    public HttpFields()
    {
        _fields=new HttpField[20];
    }
    
    /**
     * Initialize an empty HttpFields.
     * 
     * @param capacity the capacity of the http fields
     */
    public HttpFields(int capacity)
    {
        _fields=new HttpField[capacity];
    }
    
    /**
     * Initialize HttpFields from copy.
     * 
     * @param fields the fields to copy data from
     */
    public HttpFields(HttpFields fields)
    {
        _fields=Arrays.copyOf(fields._fields,fields._fields.length+10);
        _size=fields._size;
    }

    public int size()
    {
        return _size;
    }
    
    @Override
    public Iterator<HttpField> iterator()
    {
        return new Itr();
    }
    
    public Stream<HttpField> stream()
    {
        return StreamSupport.stream(Arrays.spliterator(_fields,0,_size),false);
    }

    /**
     * Get Collection of header names.
     * @return the unique set of field names.
     */
    public Set<String> getFieldNamesCollection()
    {
        final Set<String> set = new HashSet<>(_size);
        for (HttpField f : this)
        {
            if (f!=null)
                set.add(f.getName());
        }
        return set;
    }

    /**
     * Get enumeration of header _names. Returns an enumeration of strings representing the header
     * _names for this request.
     * @return an enumeration of field names
     */
    public Enumeration<String> getFieldNames()
    {
        return Collections.enumeration(getFieldNamesCollection());
    }

    /**
     * Get a Field by index.
     * @param index the field index 
     * @return A Field value or null if the Field value has not been set
     */
    public HttpField getField(int index)
    {
        if (index>=_size)
            throw new NoSuchElementException();
        return _fields[index];
    }

    public HttpField getField(HttpHeader header)
    {
        for (int i=0;i<_size;i++)
        {
            HttpField f=_fields[i];
            if (f.getHeader()==header)
                return f;
        }
        return null;
    }

    public HttpField getField(String name)
    {
        for (int i=0;i<_size;i++)
        {
            HttpField f=_fields[i];
            if (f.getName().equalsIgnoreCase(name))
                return f;
        }
        return null;
    }

    public boolean contains(HttpField field)
    {
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.isSameName(field) && (f.equals(field)||f.contains(field.getValue())))
                return true;
        }
        return false;
    }

    public boolean contains(HttpHeader header, String value)
    {
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.getHeader()==header && f.contains(value))
                return true;
        }
        return false;
    }
    
    public boolean contains(String name, String value)
    {
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.getName().equalsIgnoreCase(name) && f.contains(value))
                return true;
        }
        return false;
    }

    public boolean contains(HttpHeader header)
    {
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.getHeader()==header)
                return true;
        }
        return false;
    }
    
    public boolean containsKey(String name)
    {
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.getName().equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    @Deprecated
    public String getStringField(HttpHeader header)
    {
        return get(header);
    }
    
    public String get(HttpHeader header)
    {
        for (int i=0;i<_size;i++)
        {
            HttpField f=_fields[i];
            if (f.getHeader()==header)
                return f.getValue();
        }
        return null;
    }

    @Deprecated
    public String getStringField(String name)
    {
        return get(name);
    }
    
    public String get(String header)
    {
        for (int i=0;i<_size;i++)
        {
            HttpField f=_fields[i];
            if (f.getName().equalsIgnoreCase(header))
                return f.getValue();
        }
        return null;
    }

    /**
     * Get multiple header of the same name
     *
     * @return List the values
     * @param header the header
     */
    public List<String> getValuesList(HttpHeader header)
    {
        final List<String> list = new ArrayList<>();
        for (HttpField f : this)
            if (f.getHeader()==header)
                list.add(f.getValue());
        return list;
    }
    
    /**
     * Get multiple header of the same name
     *    
     * @return List the header values
     * @param name the case-insensitive field name
     */
    public List<String> getValuesList(String name)
    {
        final List<String> list = new ArrayList<>();
        for (HttpField f : this)
            if (f.getName().equalsIgnoreCase(name))
                list.add(f.getValue());
        return list;
    }


    /**
     * Add comma separated values, but only if not already
     * present.
     * @param header The header to add the value(s) to
     * @param values The value(s) to add
     * @return True if headers were modified
     */
    public boolean addCSV(HttpHeader header,String... values)
    {
        QuotedCSV existing = null;
        for (HttpField f : this)
        {
            if (f.getHeader()==header)
            {
                if (existing==null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        
        String value = addCSV(existing,values);
        if (value!=null)
        {
            add(header,value);
            return true;
        }
        return false;
    }
    
    /**
     * Add comma separated values, but only if not already
     * present.
     * @param name The header to add the value(s) to
     * @param values The value(s) to add
     * @return True if headers were modified
     */
    public boolean addCSV(String name,String... values)
    {
        QuotedCSV existing = null;
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                if (existing==null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        String value = addCSV(existing,values);
        if (value!=null)
        {
            add(name,value);
            return true;
        }
        return false;
    }

    protected String addCSV(QuotedCSV existing,String... values)
    {
        // remove any existing values from the new values
        boolean add = true;
        if (existing!=null && !existing.isEmpty())
        {
            add = false;
        
            for (int i=values.length;i-->0;)
            {
                String unquoted = QuotedCSV.unquote(values[i]);
                if (existing.getValues().contains(unquoted))
                    values[i] = null;
                else
                    add = true;
            }
        }
            
        if (add)
        {
            StringBuilder value = new StringBuilder();
            for (String v:values)
            {
                if (v==null)
                    continue;
                if (value.length()>0)
                    value.append(", ");
                value.append(v);
            }
            if (value.length()>0)
                return value.toString();
        }
        
        return null;
    }
    
    /**
     * Get multiple field values of the same name, split 
     * as a {@link QuotedCSV}
     *
     * @return List the values with OWS stripped
     * @param header The header
     * @param keepQuotes True if the fields are kept quoted
     */
    public List<String> getCSV(HttpHeader header,boolean keepQuotes)
    {
        QuotedCSV values = null;
        for (HttpField f : this)
        {
            if (f.getHeader()==header)
            {
                if (values==null)
                    values = new QuotedCSV(keepQuotes);
                values.addValue(f.getValue());
            }
        }
        return values==null?Collections.emptyList():values.getValues();
    }

    /**
     * Get multiple field values of the same name
     * as a {@link QuotedCSV}
     *
     * @return List the values with OWS stripped
     * @param name the case-insensitive field name
     * @param keepQuotes True if the fields are kept quoted
     */
    public List<String> getCSV(String name,boolean keepQuotes)
    {
        QuotedCSV values = null;
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                if (values==null)
                    values = new QuotedCSV(keepQuotes);
                values.addValue(f.getValue());
            }
        }
        return values==null?Collections.emptyList():values.getValues();
    }

    /**
     * Get multiple field values of the same name, split and
     * sorted as a {@link QuotedQualityCSV}
     *
     * @return List the values in quality order with the q param and OWS stripped
     * @param header The header
     */
    public List<String> getQualityCSV(HttpHeader header)
    {
        QuotedQualityCSV values = null;
        for (HttpField f : this)
        {
            if (f.getHeader()==header)
            {
                if (values==null)
                    values = new QuotedQualityCSV();
                values.addValue(f.getValue());
            }
        }

        return values==null?Collections.emptyList():values.getValues();
    }

    /**
     * Get multiple field values of the same name, split and
     * sorted as a {@link QuotedQualityCSV}
     *
     * @return List the values in quality order with the q param and OWS stripped
     * @param name the case-insensitive field name
     */
    public List<String> getQualityCSV(String name)
    {
        QuotedQualityCSV values = null;
        for (HttpField f : this)
        {
            if (f.getName().equalsIgnoreCase(name))
            {
                if (values==null)
                    values = new QuotedQualityCSV();
                values.addValue(f.getValue());
            }
        }
        return values==null?Collections.emptyList():values.getValues();
    }

    /**
     * Get multi headers
     *
     * @return Enumeration of the values
     * @param name the case-insensitive field name
     */
    public Enumeration<String> getValues(final String name)
    {
        for (int i=0;i<_size;i++)
        {
            final HttpField f = _fields[i];
            
            if (f.getName().equalsIgnoreCase(name) && f.getValue()!=null)
            {
                final int first=i;
                return new Enumeration<String>()
                {
                    HttpField field=f;
                    int i = first+1;

                    @Override
                    public boolean hasMoreElements()
                    {
                        if (field==null)
                        {
                            while (i<_size) 
                            {
                                field=_fields[i++];
                                if (field.getName().equalsIgnoreCase(name) && field.getValue()!=null)
                                    return true;
                            }
                            field=null;
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public String nextElement() throws NoSuchElementException
                    {
                        if (hasMoreElements())
                        {
                            String value=field.getValue();
                            field=null;
                            return value;
                        }
                        throw new NoSuchElementException();
                    }
                };
            }
        }

        List<String> empty=Collections.emptyList();
        return Collections.enumeration(empty);
    }

    /**
     * Get multi field values with separator. The multiple values can be represented as separate
     * headers of the same name, or by a single header using the separator(s), or a combination of
     * both. Separators may be quoted.
     *
     * @param name the case-insensitive field name
     * @param separators String of separators.
     * @return Enumeration of the values, or null if no such header.
     */
    @Deprecated
    public Enumeration<String> getValues(String name, final String separators)
    {
        final Enumeration<String> e = getValues(name);
        if (e == null)
            return null;
        return new Enumeration<String>()
        {
            QuotedStringTokenizer tok = null;

            @Override
            public boolean hasMoreElements()
            {
                if (tok != null && tok.hasMoreElements()) return true;
                while (e.hasMoreElements())
                {
                    String value = e.nextElement();
                    if (value!=null)
                    {
                        tok = new QuotedStringTokenizer(value, separators, false, false);
                        if (tok.hasMoreElements()) return true;
                    }
                }
                tok = null;
                return false;
            }

            @Override
            public String nextElement() throws NoSuchElementException
            {
                if (!hasMoreElements()) throw new NoSuchElementException();
                String next = (String) tok.nextElement();
                if (next != null) next = next.trim();
                return next;
            }
        };
    }

    public void put(HttpField field)
    {
        boolean put=false;
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.isSameName(field))
            {
                if (put)
                {
                    System.arraycopy(_fields,i+1,_fields,i,--_size-i);
                }
                else
                {
                    _fields[i]=field;
                    put=true;
                }
            }
        }
        if (!put)
            add(field);
    }
    
    /**
     * Set a field.
     *
     * @param name the name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(String name, String value)
    {
        if (value == null)
            remove(name);
        else
            put(new HttpField(name, value));
    }

    public void put(HttpHeader header, HttpHeaderValue value)
    {
        put(header,value.toString());
    }

    /**
     * Set a field.
     *
     * @param header the header name of the field
     * @param value the value of the field. If null the field is cleared.
     */
    public void put(HttpHeader header, String value)
    {
        if (value == null)
            remove(header);
        else
            put(new HttpField(header, value));
    }

    /**
     * Set a field.
     *
     * @param name the name of the field
     * @param list the List value of the field. If null the field is cleared.
     */
    public void put(String name, List<String> list)
    {
        remove(name);
        for (String v : list)
            if (v!=null)
                add(name,v);
    }

    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     *
     * @param name the name of the field
     * @param value the value of the field.
     */
    public void add(String name, String value)
    {
        if (value == null)
            return;

        HttpField field = new HttpField(name, value);
        add(field);
    }

    public void add(HttpHeader header, HttpHeaderValue value)
    {
        add(header,value.toString());
    }

    /**
     * Add to or set a field. If the field is allowed to have multiple values, add will add multiple
     * headers of the same name.
     *
     * @param header the header
     * @param value the value of the field.
     */
    public void add(HttpHeader header, String value)
    {
        if (value == null) throw new IllegalArgumentException("null value");

        HttpField field = new HttpField(header, value);
        add(field);
    }

    /**
     * Remove a field.
     *
     * @param name the field to remove
     * @return the header that was removed
     */
    public HttpField remove(HttpHeader name)
    {
        HttpField removed=null;
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.getHeader()==name)
            {
                removed=f;
                System.arraycopy(_fields,i+1,_fields,i,--_size-i);
            }
        }
        return removed;
    }

    /**
     * Remove a field.
     *
     * @param name the field to remove
     * @return the header that was removed
     */
    public HttpField remove(String name)
    {
        HttpField removed=null;
        for (int i=_size;i-->0;)
        {
            HttpField f=_fields[i];
            if (f.getName().equalsIgnoreCase(name))
            {
                removed=f;
                System.arraycopy(_fields,i+1,_fields,i,--_size-i);
            }
        }
        return removed;
    }

    /**
     * Get a header as an long value. Returns the value of an integer field or -1 if not found. The
     * case of the field name is ignored.
     *
     * @param name the case-insensitive field name
     * @return the value of the field as a long
     * @exception NumberFormatException If bad long found
     */
    public long getLongField(String name) throws NumberFormatException
    {
        HttpField field = getField(name);
        return field==null?-1L:field.getLongValue();
    }

    /**
     * Get a header as a date value. Returns the value of a date field, or -1 if not found. The case
     * of the field name is ignored.
     *
     * @param name the case-insensitive field name
     * @return the value of the field as a number of milliseconds since unix epoch
     */
    public long getDateField(String name)
    {
        HttpField field = getField(name);
        if (field == null)
            return -1;

        String val = valueParameters(field.getValue(), null);
        if (val == null)
            return -1;

        final long date = DateParser.parseDate(val);
        if (date==-1)
            throw new IllegalArgumentException("Cannot convert date: " + val);
        return date;
    }


    /**
     * Sets the value of an long field.
     *
     * @param name the field name
     * @param value the field long value
     */
    public void putLongField(HttpHeader name, long value)
    {
        String v = Long.toString(value);
        put(name, v);
    }

    /**
     * Sets the value of an long field.
     *
     * @param name the field name
     * @param value the field long value
     */
    public void putLongField(String name, long value)
    {
        String v = Long.toString(value);
        put(name, v);
    }


    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     */
    public void putDateField(HttpHeader name, long date)
    {
        String d=DateGenerator.formatDate(date);
        put(name, d);
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     */
    public void putDateField(String name, long date)
    {
        String d=DateGenerator.formatDate(date);
        put(name, d);
    }

    /**
     * Sets the value of a date field.
     *
     * @param name the field name
     * @param date the field date value
     */
    public void addDateField(String name, long date)
    {
        String d=DateGenerator.formatDate(date);
        add(name,d);
    }

    @Override
    public int hashCode()
    {
        int hash=0;
        for (HttpField field:_fields)
            hash+=field.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof HttpFields))
            return false;

        HttpFields that = (HttpFields)o;

        // Order is not important, so we cannot rely on List.equals().
        if (size() != that.size())
            return false;

        loop: for (HttpField fi : this)
        {
            for (HttpField fa : that)
            {
                if (fi.equals(fa))
                    continue loop;
            }
            return false;
        }
        return true;
    }

    @Override
    public String toString()
    {
        try
        {
            StringBuilder buffer = new StringBuilder();
            for (HttpField field : this)
            {
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

    public void clear()
    {
        _size=0;
    }
    
    public void add(HttpField field)
    {
        if (field!=null)
        {
            if (_size==_fields.length)
                _fields=Arrays.copyOf(_fields,_size*2);
            _fields[_size++]=field;
        }
    }

    public void addAll(HttpFields fields)
    {
        for (int i=0;i<fields._size;i++)
            add(fields._fields[i]);
    }

    /**
     * Add fields from another HttpFields instance. Single valued fields are replaced, while all
     * others are added.
     *
     * @param fields the fields to add
     */
    public void add(HttpFields fields)
    {
        if (fields == null) return;

        Enumeration<String> e = fields.getFieldNames();
        while (e.hasMoreElements())
        {
            String name = e.nextElement();
            Enumeration<String> values = fields.getValues(name);
            while (values.hasMoreElements())
                add(name, values.nextElement());
        }
    }

    /**
     * Get field value without parameters. Some field values can have parameters. This method separates the
     * value from the parameters and optionally populates a map with the parameters. For example:
     *
     * <PRE>
     *
     * FieldName : Value ; param1=val1 ; param2=val2
     *
     * </PRE>
     *
     * @param value The Field value, possibly with parameters.
     * @return The value.
     */
    public static String stripParameters(String value)
    {
        if (value == null) return null;

        int i = value.indexOf(';');
        if (i < 0) return value;
        return value.substring(0, i).trim();
    }

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
     * @param value The Field value, possibly with parameters.
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

    @Deprecated
    private static final Float __one = new Float("1.0");
    @Deprecated
    private static final Float __zero = new Float("0.0");
    @Deprecated
    private static final Trie<Float> __qualities = new ArrayTernaryTrie<>();
    static
    {
        __qualities.put("*", __one);
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

    @Deprecated
    public static Float getQuality(String value)
    {
        if (value == null) return __zero;

        int qe = value.indexOf(";");
        if (qe++ < 0 || qe == value.length()) return __one;

        if (value.charAt(qe++) == 'q')
        {
            qe++;
            Float q = __qualities.get(value, qe, value.length() - qe);
            if (q != null)
                return q;
        }

        Map<String,String> params = new HashMap<>(4);
        valueParameters(value, params);
        String qs = params.get("q");
        if (qs==null)
            qs="*";
        Float q = __qualities.get(qs);
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

    /**
     * List values in quality order.
     *
     * @param e Enumeration of values with quality parameters
     * @return values in quality order.
     */
    @Deprecated
    public static List<String> qualityList(Enumeration<String> e)
    {
        if (e == null || !e.hasMoreElements())
            return Collections.emptyList();

        QuotedQualityCSV values = new QuotedQualityCSV();
        while(e.hasMoreElements())
            values.addValue(e.nextElement());
        return values.getValues();
    }


    private class Itr implements Iterator<HttpField> 
    {
        int _cursor;       // index of next element to return
        int _last=-1;

        public boolean hasNext() 
        {
            return _cursor != _size;
        }

        public HttpField next() 
        {
            int i = _cursor;
            if (i >= _size)
                throw new NoSuchElementException();
            _cursor = i + 1;
            return _fields[_last=i];
        }

        public void remove() 
        {
            if (_last<0)
                throw new IllegalStateException();

            System.arraycopy(_fields,_last+1,_fields,_last,--_size-_last);
            _cursor=_last;
            _last=-1;
        }
    }

}
