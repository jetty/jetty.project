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
     * @return the response cookie name
     */
    public String getName()
    {
        return _name;
    }

    /**
     * @param name the response cookie name
     */
    public void setName(String name)
    {
        _name = name;
    }

    /**
     * @return the response cookie value
     */
    public String getValue()
    {
        return _value;
    }

    /**
     * @param value the response cookie value
     */
    public void setValue(String value)
    {
        _value = value;
    }

    @Override
    public RequestProcessor apply(RequestProcessor input) throws IOException
    {
        // TODO: fix once Request.getCookies() is implemented (currently always returns null)
        // Check that cookie is not already set
        List<HttpCookie> cookies = Request.getCookies(input);
        if (cookies != null)
        {
            for (HttpCookie cookie : cookies)
            {
                if (_name.equals(cookie.getName()) && _value.equals(cookie.getValue()))
                    return null;
            }
        }

        return new RequestProcessor(input)
        {
            @Override
            public void process(Request ignored, Response response, Callback callback) throws Exception
            {
                Response.addCookie(response, new HttpCookie(_name, _value));
                super.process(this, response, callback);
            }
        };
    }

    @Override
    public String toString()
    {
        return "%s@%x[set-cookie:%s=%s]".formatted(super.toString(), hashCode(), getName(), getValue());
    }
}
