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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Sends the response code whenever the rule finds a match.
 */
public class ResponsePatternRule extends PatternRule
{
    private int _code;
    private String _message;

    public ResponsePatternRule()
    {
    }

    public ResponsePatternRule(@Name("pattern") String pattern, @Name("code") int code, @Name("message") String message)
    {
        super(pattern);
        _code = code;
        _message = message;
    }

    @Override
    public boolean isTerminating()
    {
        return true;
    }

    public int getCode()
    {
        return _code;
    }

    /**
     * @param code the response code
     */
    public void setCode(int code)
    {
        _code = code;
    }

    public String getMessage()
    {
        return _message;
    }

    /**
     * <p>Sets the message for the response body (if the response code may have a body).</p>
     *
     * @param message the response message
     */
    public void setMessage(String message)
    {
        _message = message;
    }

    @Override
    public RequestProcessor apply(RequestProcessor input) throws IOException
    {
        if (getCode() < HttpStatus.CONTINUE_100)
            return null;

        return new RequestProcessor(input)
        {
            @Override
            public void process(Request ignored, Response response, Callback callback)
            {
                String message = getMessage();
                if (StringUtil.isBlank(message))
                {
                    response.setStatus(getCode());
                    callback.succeeded();
                }
                else
                {
                    Response.writeError(this, response, callback, getCode(), message);
                }
            }
        };
    }

    @Override
    public String toString()
    {
        return "%s[response:%d>%s]".formatted(super.toString(), getCode(), getMessage());
    }
}
