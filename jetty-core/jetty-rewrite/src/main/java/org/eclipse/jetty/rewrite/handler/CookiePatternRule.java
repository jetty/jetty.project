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
import java.util.List;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.Name;

/**
 * <p>Sets a response cookie whenever the rule matches.</p>
 *
 * @see HttpCookie
 */
public class CookiePatternRule extends PatternRule
{
    private String _name;
    private String _value;

    public CookiePatternRule()
    {
    }

    public CookiePatternRule(@Name("pattern") String pattern, @Name("name") String name, @Name("value") String value)
    {
        super(pattern);
        setName(name);
        setValue(value);
    }

    /**
     * Get the response cookie name.
     * @return the response cookie name
     */
    public String getName()
    {
        return _name;
    }

    /**
     * Set the response cookie name.
     * @param name the response cookie name
     */
    public void setName(String name)
    {
        _name = name;
    }

    /**
     * Get the response cookie value.
     * @return the response cookie value
     */
    public String getValue()
    {
        return _value;
    }

    /**
     * Set the response cookie value.
     * @param value the response cookie value
     */
    public void setValue(String value)
    {
        _value = value;
    }

    @Override
    public Handler apply(Handler input) throws IOException
    {
        // Check that the cookie is not already set.
        List<HttpCookie> cookies = Request.getCookies(input);
        for (HttpCookie cookie : cookies)
        {
            if (_name.equals(cookie.getName()) && _value.equals(cookie.getValue()))
                return null;
        }

        return new Handler(input)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Response.addCookie(response, HttpCookie.from(_name, _value));
                return super.handle(request, response, callback);
            }
        };
    }

    @Override
    public String toString()
    {
        return "%s@%x[set-cookie:%s=%s]".formatted(super.toString(), hashCode(), getName(), getValue());
    }
}
