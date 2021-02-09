//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
     * @param replacement the URI to redirect to
     * @deprecated use {@link #setLocation(String)} instead.
     */
    @Deprecated
    public void setReplacement(String replacement)
    {
        setLocation(replacement);
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
