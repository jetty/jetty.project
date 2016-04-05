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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.annotation.Name;


/**
 * Sets the header in the response whenever the rule finds a match.
 */
public class HeaderPatternRule extends PatternRule
{
    private String _name;
    private String _value;
    private boolean _add;

    /* ------------------------------------------------------------ */
    public HeaderPatternRule()
    {
        this(null,null,null);
    }

    /* ------------------------------------------------------------ */
    public HeaderPatternRule(@Name("pattern") String pattern, @Name("name") String name, @Name("value") String value)
    {
        super(pattern);
        _handling = false;
        _terminating = false;
        _add=false;
        setName(name);
        setValue(value);
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the header name.
     * 
     * @param name name of the header field
     */
    public void setName(String name)
    {
        _name = name;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the header value. The value can be either a <code>String</code> or <code>int</code> value.
     * 
     * @param value of the header field
     */
    public void setValue(String value)
    {
        _value = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the Add flag. 
     * @param add If true, the header is added to the response, otherwise the header it is set on the response.
     */
    public void setAdd(boolean add)
    {
        _add = add;
    }

    /* ------------------------------------------------------------ */
    /**
     * Invokes this method when a match found. If the header had already been set, 
     * the new value overwrites the previous one. Otherwise, it adds the new 
     * header name and value.
     * 
     *@see org.eclipse.jetty.rewrite.handler.Rule#matchAndApply(String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // process header
        if (_add)
            response.addHeader(_name, _value);
        else
            response.setHeader(_name, _value); 
        return target;
    }
    
    

    /* ------------------------------------------------------------ */
    /**
     * Returns the header name.
     * @return the header name.
     */
    public String getName()
    {
        return _name;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the header value.
     * @return the header value.
     */
    public String getValue()
    {
        return _value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the add flag value.
     * @return true if add flag set
     */
    public boolean isAdd()
    {
        return _add;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the header contents.
     */
    @Override
    public String toString()
    {
        return super.toString()+"["+_name+","+_value+"]";
    }
}
