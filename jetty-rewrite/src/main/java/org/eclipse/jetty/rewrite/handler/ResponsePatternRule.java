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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
        this(null, null, null);
    }

    public ResponsePatternRule(@Name("pattern") String pattern, @Name("code") String code, @Name("message") String message)
    {
        super(pattern);
        _handling = true;
        _terminating = true;
        setCode(code);
        setMessage(message);
    }

    /**
     * Sets the response status code.
     *
     * @param code response code
     */
    public void setCode(String code)
    {
        _code = code == null ? 0 : Integer.parseInt(code);
    }

    /**
     * Sets the message for the {@link org.eclipse.jetty.server.Response#sendError(int, String)} method.
     * Reasons will only reflect
     * if the code value is greater or equal to 400.
     *
     * @param message the reason
     */
    public void setMessage(String message)
    {
        _message = message;
    }

    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // status code 400 and up are error codes
        if (_code > 0)
        {
            if (_message != null && !_message.isEmpty())
                response.sendError(_code, _message);
            else
                response.setStatus(_code);
        }
        return target;
    }

    /**
     * Returns the code and reason string.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _code + "," + _message + "]";
    }
}
