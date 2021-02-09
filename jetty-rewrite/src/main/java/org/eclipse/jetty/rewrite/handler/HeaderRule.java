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

/**
 * Abstract rule that matches against request headers.
 */

public abstract class HeaderRule extends Rule
{
    private String _header;
    private String _headerValue;

    public String getHeader()
    {
        return _header;
    }

    /**
     * @param header the header name to check for
     */
    public void setHeader(String header)
    {
        _header = header;
    }

    public String getHeaderValue()
    {
        return _headerValue;
    }

    /**
     * @param headerValue the header value to match against. If null, then the
     * presence of the header is enough to match
     */
    public void setHeaderValue(String headerValue)
    {
        _headerValue = headerValue;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request,
                                HttpServletResponse response) throws IOException
    {
        String requestHeaderValue = request.getHeader(_header);

        if (requestHeaderValue != null)
            if (_headerValue == null || _headerValue.equals(requestHeaderValue))
                apply(target, requestHeaderValue, request, response);

        return null;
    }

    /**
     * Apply the rule to the request
     *
     * @param target field to attempt match
     * @param value header value found
     * @param request request object
     * @param response response object
     * @return The target (possible updated)
     * @throws IOException exceptions dealing with operating on request or response
     * objects
     */
    protected abstract String apply(String target, String value, HttpServletRequest request, HttpServletResponse response) throws IOException;

    @Override
    public String toString()
    {
        return super.toString() + "[" + _header + ":" + _headerValue + "]";
    }
}
