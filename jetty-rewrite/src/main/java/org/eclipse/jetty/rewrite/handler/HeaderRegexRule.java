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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.annotation.Name;

/**
 * Rule to add a header based on a Regex match
 */
public class HeaderRegexRule extends RegexRule
{
    private String _name;
    private String _value;
    private boolean _add = false;

    public HeaderRegexRule()
    {
        this(null, null, null);
    }

    public HeaderRegexRule(@Name("regex") String regex, @Name("name") String name, @Name("value") String value)
    {
        super(regex);
        setHandling(false);
        setTerminating(false);
        setName(name);
        setValue(value);
    }

    /**
     * Sets the header name.
     *
     * @param name name of the header field
     */
    public void setName(String name)
    {
        _name = name;
    }

    /**
     * Sets the header value. The value can be either a <code>String</code> or <code>int</code> value.
     *
     * @param value of the header field
     */
    public void setValue(String value)
    {
        _value = value;
    }

    /**
     * Sets the Add flag.
     *
     * @param add If true, the header is added to the response, otherwise the header it is set on the response.
     */
    public void setAdd(boolean add)
    {
        _add = add;
    }

    @Override
    protected String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher)
        throws IOException
    {
        // process header
        if (_add)
            response.addHeader(_name, _value);
        else
            response.setHeader(_name, _value);
        return target;
    }

    /**
     * Returns the header name.
     *
     * @return the header name.
     */
    public String getName()
    {
        return _name;
    }

    /**
     * Returns the header value.
     *
     * @return the header value.
     */
    public String getValue()
    {
        return _value;
    }

    /**
     * @return the add flag value.
     */
    public boolean isAdd()
    {
        return _add;
    }

    /**
     * @return the header contents.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _name + "," + _value + "]";
    }
}
