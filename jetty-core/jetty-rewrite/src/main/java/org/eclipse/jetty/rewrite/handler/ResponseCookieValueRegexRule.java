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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Sends a response with the configured status code whenever the value of the configured cookie matches (or does not match) a regular expression.
 */
public class ResponseCookieValueRegexRule extends Rule
{
    private String _cookieName;
    private Pattern _cookieValueRegex;
    private boolean _negate = false;
    private int _code;
    private String _message;

    public ResponseCookieValueRegexRule()
    {
    }

    @Override
    public boolean isTerminating()
    {
        return true;
    }

    public String getCookieName()
    {
        return _cookieName;
    }

    /**
     * Set the cookie to match on
     * @param cookieName the cookie to match on
     */
    public void setCookieName(String cookieName)
    {
        _cookieName = cookieName;
    }

    public String getCookieValueRegex()
    {
        return _cookieValueRegex == null ? null : _cookieValueRegex.pattern();
    }

    /**
     * Set the regex to match against the cookie value, null to match on any value
     * @param cookieValueRegex regex to match against the cookie value
     */
    public void setCookieValueRegex(String cookieValueRegex)
    {
        _cookieValueRegex = cookieValueRegex == null ? null : Pattern.compile(cookieValueRegex);
    }

    public boolean isNegate()
    {
        return _negate;
    }

    /**
     * Set whether to negate the result of the match.
     * @param negate whether to negate the result of the match
     */
    public void setNegate(boolean negate)
    {
        _negate = negate;
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
        boolean found = false;

        List<HttpCookie> cookies = Request.getCookies(input);
        for (HttpCookie cookie : cookies)
        {
            if (_cookieName.equals(cookie.getName()))
            {
                if (_cookieValueRegex == null)
                {
                    found = true;
                }
                else
                {
                    Matcher matcher = _cookieValueRegex.matcher(cookie.getValue());
                    found = matcher.matches();
                }
                break;
            }
        }

        if (found ^ _negate)
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
        else
        {
            return null;
        }
    }

    @Override
    public String toString()
    {
        return "%s[%scookie:%s=%s][response:%d>%s]".formatted(super.toString(), isNegate() ? "!" : "", getCookieName(), getCookieValueRegex(), getCode(), getMessage());
    }
}
