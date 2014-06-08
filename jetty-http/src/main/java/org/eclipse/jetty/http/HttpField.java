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

    public int nameHashCode()
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
    
    
    
}
