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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Issues a (3xx) Redirect response whenever the rule finds a match.
 * <p>
 * All redirects are part of the <a href="http://tools.ietf.org/html/rfc7231#section-6.4"><code>3xx Redirection</code> status code set</a>.
 * <p>
 * Defaults to <a href="http://tools.ietf.org/html/rfc7231#section-6.4.3"><code>302 Found</code></a>
 */
public class RedirectPatternRule extends PatternRule
{
    private String _location;
    private int _statusCode = HttpStatus.FOUND_302;

    public RedirectPatternRule()
    {
        this(null, null);
    }

    public RedirectPatternRule(@Name("pattern") String pattern, @Name("location") String location)
    {
        super(pattern);
        _handling = true;
        _terminating = true;
        _location = location;
    }

    /**
     * Sets the redirect location.
     *
     * @param value the location to redirect.
     */
    public void setLocation(String value)
    {
        _location = value;
    }

    /**
     * Sets the redirect status code.
     *
     * @param statusCode the 3xx redirect status code
     */
    public void setStatusCode(int statusCode)
    {
        if ((300 <= statusCode) || (statusCode >= 399))
        {
            _statusCode = statusCode;
        }
        else
        {
            throw new IllegalArgumentException("Invalid redirect status code " + statusCode + " (must be a value between 300 and 399)");
        }
    }

    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String location = response.encodeRedirectURL(_location);
        response.setHeader("Location", RedirectUtil.toRedirectURL(request, location));
        response.setStatus(_statusCode);
        response.getOutputStream().flush(); // no output / content
        response.getOutputStream().close();
        return target;
    }

    /**
     * Returns the redirect status code and location.
     */
    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(super.toString());
        str.append('[').append(_statusCode);
        str.append('>').append(_location);
        str.append(']');
        return str.toString();
    }
}
