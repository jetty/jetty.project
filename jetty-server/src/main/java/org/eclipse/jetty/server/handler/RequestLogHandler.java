//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
