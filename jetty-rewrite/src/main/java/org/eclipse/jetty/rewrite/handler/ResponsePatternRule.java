// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Sends the response code whenever the rule finds a match.
 */
public class ResponsePatternRule extends PatternRule
{
    private String _code;
    private String _reason = "";

    /* ------------------------------------------------------------ */
    public ResponsePatternRule()
    {
        _handling = true;
        _terminating = true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the response status code. 
     * @param code response code
     */
    public void setCode(String code)
    {
        _code = code;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the reason for the response status code. Reasons will only reflect
     * if the code value is greater or equal to 400.
     * 
     * @param reason
     */
    public void setReason(String reason)
    {
        _reason = reason;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        int code = Integer.parseInt(_code);

        // status code 400 and up are error codes
        if (code >= 400)
        {
            response.sendError(code, _reason);
        }
        else
        {
            response.setStatus(code);
        }
        return target;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the code and reason string.
     */
    public String toString()
    {
        return super.toString()+"["+_code+","+_reason+"]";
    }
}
