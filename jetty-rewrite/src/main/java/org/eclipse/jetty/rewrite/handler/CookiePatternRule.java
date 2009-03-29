// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * Sets the cookie in the response whenever the rule finds a match.
 * 
 * @see Cookie
 */
public class CookiePatternRule extends PatternRule
{
    private String _name;
    private String _value;

    /* ------------------------------------------------------------ */
    public CookiePatternRule()
    {
        _handling = false;
        _terminating = false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Assigns the cookie name.
     * 
     * @param name a <code>String</code> specifying the name of the cookie.
     */
    public void setName(String name)
    {
        _name = name;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.addCookie(new Cookie(_name, _value));
        return target;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the cookie contents.
     */
    public String toString()
    {
        return super.toString()+"["+_name+","+_value + "]";
    }
}
