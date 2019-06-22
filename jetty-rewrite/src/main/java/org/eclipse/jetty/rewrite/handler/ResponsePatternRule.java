//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

    /*
     * (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
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
