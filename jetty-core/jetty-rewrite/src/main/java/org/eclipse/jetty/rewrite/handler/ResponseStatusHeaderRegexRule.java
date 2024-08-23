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
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Sends a response with the configured status code whenever the value of the configured request header matches a regular expression.
 */
public class ResponseStatusHeaderRegexRule extends Rule
{
    private String _headerName;
    private Pattern _headerRegex;
    private int _code;
    private String _message;

    public ResponseStatusHeaderRegexRule()
    {
    }

    @Override
    public boolean isTerminating()
    {
        return true;
    }

    public String getHeaderName()
    {
        return _headerName;
    }

    /**
     * Set the http header to match on
     * @param headerName the http header to match on
     */
    public void setHeaderName(String headerName)
    {
        _headerName = headerName;
    }

    public String getHeaderRegex()
    {
        return _headerRegex == null ? null : _headerRegex.pattern();
    }

    /**
     * Set the regex to match against the header value, null to match on any value
     * @param headerRegex regex to match against the header value
     */
    public void setHeaderRegex(String headerRegex)
    {
        _headerRegex = headerRegex == null ? null : Pattern.compile(headerRegex);
    }

    public int getCode()
    {
        return _code;
    }

    /**
     * Set the http status code returned on a match.
     * @param code the http status code
     */
    public void setCode(int code)
    {
        if (code < HttpStatus.CONTINUE_100)
            throw new IllegalArgumentException("invalid http status code");

        _code = code;
    }

    public String getMessage()
    {
        return _message;
    }

    /**
     * <p>Sets the message for the response body.</p>
     *
     * @param message the response body message
     */
    public void setMessage(String message)
    {
        _message = message;
    }

    @Override
    public Handler matchAndApply(Handler input) throws IOException
    {
        String value = input.getHeaders().get(getHeaderName());
        if (value == null)
            return null;
        if (_headerRegex == null)
            return apply(input, value);
        Matcher matcher = _headerRegex.matcher(value);
        if (matcher.matches())
            return apply(input, value);
        return null;
    }

    public Handler apply(Handler input, String value) throws IOException
    {
        return new Handler(input)
        {
            @Override
            protected boolean handle(Response response, Callback callback)
            {
                Response.writeError(this, response, callback, _code, _message);
                return true;
            }
        };
    }

    @Override
    public String toString()
    {
        return "%s[header:%s=%s][response:%d>%s]".formatted(super.toString(), getHeaderName(), getHeaderRegex(), getCode(), getMessage());
    }
}
