//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;

/**
 * <p>This handler provides an alternate way (other than {@link Server#setRequestLog(RequestLog)})
 * to log request, that can be applied to a particular handler (eg context).
 * This handler can be used to wrap an individual context for context logging, or can be listed
 * prior to a handler.
 * </p>
 *
 * @see Server#setRequestLog(RequestLog)
 */
public class RequestLogHandler extends HandlerWrapper
{
    private RequestLog _requestLog;

    /*
     * @see org.eclipse.jetty.server.server.Handler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if (baseRequest.getDispatcherType() == DispatcherType.REQUEST)
            baseRequest.getHttpChannel().addRequestLog(_requestLog);
        if (_handler != null)
            _handler.handle(target, baseRequest, request, response);
    }

    public void setRequestLog(RequestLog requestLog)
    {
        updateBean(_requestLog, requestLog);
        _requestLog = requestLog;
    }

    public RequestLog getRequestLog()
    {
        return _requestLog;
    }
}
