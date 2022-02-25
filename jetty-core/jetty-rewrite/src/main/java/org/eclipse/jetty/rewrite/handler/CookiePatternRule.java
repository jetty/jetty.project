//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Sets the cookie in the response whenever the rule finds a match.
 *
 * @see Cookie
 */
public class CookiePatternRule extends PatternRule
{
    private String _name;
    private String _value;

    public CookiePatternRule()
    {
        this(null, null, null);
    }

    public CookiePatternRule(@Name("pattern") String pattern, @Name("name") String name, @Name("value") String value)
    {
        super(pattern);
        _handling = false;
        _terminating = false;
        setName(name);
        setValue(value);
    }

    /**
     * Assigns the cookie name.
     *
     * @param name a <code>String</code> specifying the name of the cookie.
     */
    public void setName(String name)
    {
        _name = name;
    }

    /**
     * Assigns the cookie value.
     *
     * @param value a <code>String</code> specifying the value of the cookie
     * @see Cookie#setValue(String)
     */
    public void setValue(String value)
    {
        _value = value;
    }

    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // Check that cookie is not already set
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_name.equals(cookie.getName()) && _value.equals(cookie.getValue()))
                    return target;
            }
        }

        // set it
        response.addCookie(new Cookie(_name, _value));
        return target;
    }

    /**
     * Returns the cookie contents.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _name + "," + _value + "]";
    }
}
