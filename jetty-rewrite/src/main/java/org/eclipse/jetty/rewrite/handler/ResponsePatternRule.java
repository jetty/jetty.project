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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Sends the response code whenever the rule finds a match.
 */
public class ResponsePatternRule extends PatternRule
{
    private String _code;
    private String _reason;

    public ResponsePatternRule()
    {
        this(null, null, "");
    }

    public ResponsePatternRule(@Name("pattern") String pattern, @Name("code") String code, @Name("reason") String reason)
    {
        super(pattern);
        _handling = true;
        _terminating = true;
        setCode(code);
        setReason(reason);
    }

    /**
     * Sets the response status code.
     *
     * @param code response code
     */
    public void setCode(String code)
    {
        _code = code;
    }

    /**
     * Sets the reason for the response status code. Reasons will only reflect
     * if the code value is greater or equal to 400.
     *
     * @param reason the reason
     */
    public void setReason(String reason)
    {
        _reason = reason;
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        int code = Integer.parseInt(_code);

        // status code 400 and up are error codes
        if (code >= 400)
        {
            if (!StringUtil.isBlank(_reason))
            {
                // use both setStatusWithReason (to set the reason) and sendError to set the message
                Request.getBaseRequest(request).getResponse().setStatusWithReason(code, _reason);
                response.sendError(code, _reason);
            }
            else
            {
                response.sendError(code);
            }
        }
        else
        {
            response.setStatus(code, _reason);
        }
        return target;
    }

    /**
     * Returns the code and reason string.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _code + "," + _reason + "]";
    }
}
