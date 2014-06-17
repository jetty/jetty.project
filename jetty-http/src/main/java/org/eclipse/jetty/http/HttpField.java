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

import java.util.ArrayList;

/* ------------------------------------------------------------ */
/** A HTTP Field
 */
public class HttpField
{
    private final HttpHeader _header;
    private final String _name;
    private final String _value;
        
    public HttpField(HttpHeader header, String name, String value)
    {
        _header = header;
        _name = name;
        _value = value;
    }  
    
    public HttpField(HttpHeader header, String value)
    {
        this(header,header.asString(),value);
    }
    
    public HttpField(HttpHeader header, HttpHeaderValue value)
    {
        this(header,header.asString(),value.asString());
    }
    
    public HttpField(String name, String value)
    {
        this(HttpHeader.CACHE.get(name),name,value);
    }

    public HttpHeader getHeader()
    {
        return _header;
    }

    public String getName()
    {
        return _name;
    }

    public String getValue()
    {
        return _value;
    }
    
    public String[] getValues()
    {  
        ArrayList<String> list = new ArrayList<>(); 
        int state = 0;
        int start=0;
        int end=0;
        StringBuilder builder = new StringBuilder();

        for (int i=0;i<_value.length();i++)
        {
            char c = _value.charAt(i);
            switch(state)
            {
                case 0: // initial white space
                    switch(c)
                    {
                        case '"': // open quote
                            state=2;
                            break;

                        case ',': // leading empty field
                            list.add("");
                            break;

                        case ' ': // more white space
                        case '\t':
                            break;

                        default: // character
                            start=i;
                            end=i;
                            state=1;
                    }
                    break;

                case 1: // In token
                    switch(c)
                    {
                        case ',': // next field
                            list.add(_value.substring(start,end+1));
                            state=0;
                            break;

                        case ' ': // more white space
                        case '\t':
                            break;

                        default: 
                            end=i;
                    }
                    break;

                case 2: // In Quoted
                    switch(c)
                    {
                        case '\\': // next field
                            state=3;
                            break;

                        case '"': // end quote
                            list.add(builder.toString());
                            builder.setLength(0);
                            state=4;
                            break;

                        default: 
                            builder.append(c);
                    }
                    break;

                case 3: // In Quoted Quoted
                    builder.append(c);
                    state=2;
                    break;

                case 4: // WS after end quote
                    switch(c)
                    {
                        case ' ': // white space
                        case '\t': // white space
                            break;

                        case ',': // white space
                            state=0;
                            break;

                        default: 
                            throw new IllegalArgumentException("c="+(int)c);

                    }
                    break;
            }
        }

        switch(state)
        {
            case 0:
                break;
            case 1:
                list.add(_value.substring(start,end+1));
                break;
            case 4:
                break;

            default:
                throw new IllegalArgumentException("state="+state);
        }

        return list.toArray(new String[list.size()]);
    }

    /* ------------------------------------------------------------ */
    /** Look for a value in a possible multi valued field
     * @param value
     * @return True iff the value is contained in the field value entirely or
     * as an element of a quoted comma separated list
     */
    public boolean contains(String value)
    {
        if (_value==null)
            return value==null;

        if (_value.equalsIgnoreCase(value))
            return true;
        
        String[] values = getValues();
        for (String v:values)
            if (v.equalsIgnoreCase(value))
                return true;

        return false;
    }
    
    @Override
    public String toString()
    {
        String v=getValue();
        return getName() + ": " + (v==null?"":v);
    }

    public boolean isSameName(HttpField field)
    {
        if (field==null)
            return false;
        if (field==this)
            return true;
        if (_header!=null && _header==field.getHeader())
            return true;
        if (_name.equalsIgnoreCase(field.getName()))
            return true;
        return false;
    }
    
    private int nameHashCode()
    {
        int hash=13;
        int len = _name.length();
        for (int i = 0; i < len; i++)
        {
            char c = Character.toUpperCase(_name.charAt(i));
            hash = 31 * hash + c;
        }
        return hash;
    }

    @Override
    public int hashCode()
    {
        if (_header==null)
            return _value.hashCode() ^ nameHashCode();
        return _value.hashCode() ^ _header.hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        if (o==this)
            return true;
        if (!(o instanceof HttpField))
            return false;
        HttpField field=(HttpField)o;
        if (_header!=field.getHeader())
            return false;
        if (!_name.equalsIgnoreCase(field.getName()))
            return false;
        if (_value==null && field.getValue()!=null)
            return false;
        if (!_value.equals(field.getValue()))
            return false;
        return true;
    }
}
