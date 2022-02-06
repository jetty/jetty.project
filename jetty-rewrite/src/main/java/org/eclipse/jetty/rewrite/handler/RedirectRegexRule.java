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
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Issues a (3xx) Redirect response whenever the rule finds a match via regular expression.
 * <p>
 * The replacement string may use $n" to replace the nth capture group.
 * <p>
 * All redirects are part of the <a href="http://tools.ietf.org/html/rfc7231#section-6.4">{@code 3xx Redirection} status code set</a>.
 * <p>
 * Defaults to <a href="http://tools.ietf.org/html/rfc7231#section-6.4.3">{@code 302 Found}</a>
 */
public class RedirectRegexRule extends RegexRule
{
    protected String _location;
    private int _statusCode = HttpStatus.FOUND_302;

    public RedirectRegexRule()
    {
        this(null, null);
    }

    public RedirectRegexRule(@Name("regex") String regex, @Name("location") String location)
    {
        super(regex);
        setHandling(true);
        setTerminating(true);
        setLocation(location);
    }

    /**
     * Sets the redirect location.
     *
     * @param location the URI to redirect to
     */
    public void setLocation(String location)
    {
        _location = location;
    }

    /**
     * Sets the redirect status code.
     *
     * @param statusCode the 3xx redirect status code
     */
    public void setStatusCode(int statusCode)
    {
        if (statusCode >= 300 && statusCode <= 399)
            _statusCode = statusCode;
        else
            throw new IllegalArgumentException("Invalid redirect status code " + statusCode + " (must be a value between 300 and 399)");
    }

    @Override
    protected String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
    {
        target = _location;
        for (int g = 1; g <= matcher.groupCount(); g++)
        {
            String group = matcher.group(g);
            target = StringUtil.replace(target, "$" + g, group);
        }

        target = response.encodeRedirectURL(target);
        response.setHeader("Location", RedirectUtil.toRedirectURL(request, target));
        response.setStatus(_statusCode);
        response.getOutputStream().flush(); // no output / content
        response.getOutputStream().close();
        return target;
    }

    /**
     * Returns the redirect status code and replacement.
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
