//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.AsyncContextState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/**
 * RequestLogHandler.
 * This handler can be used to wrap an individual context for context logging.
 *
 *
 * @org.apache.xbean.XBean
 */
public class RequestLogHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(RequestLogHandler.class);
    private RequestLog _requestLog;
    private final AsyncListener _listener = new AsyncListener()
    {
        
        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            
        }
        
        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().addListener(this);
        }
        
        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            
        }
        
        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            AsyncContextState context = (AsyncContextState)event.getAsyncContext();
            Request request=context.getHttpChannelState().getBaseRequest();
            Response response=request.getResponse();
            _requestLog.log(request,response);
        }
    };

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.Handler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        try
        {
            super.handle(target, baseRequest, request, response);
        }
        finally
        {
            if (_requestLog != null && baseRequest.getDispatcherType().equals(DispatcherType.REQUEST))
            {
                if (baseRequest.getHttpChannelState().isAsync())
                {
                    if (baseRequest.getHttpChannelState().isInitial())
                        baseRequest.getAsyncContext().addListener(_listener);
                }
                else
                    _requestLog.log(baseRequest, (Response)response);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void setRequestLog(RequestLog requestLog)
    {
        updateBean(_requestLog,requestLog);
        _requestLog=requestLog;
    }

    /* ------------------------------------------------------------ */
    public RequestLog getRequestLog()
    {
        return _requestLog;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (_requestLog==null)
        {
            LOG.warn("!RequestLog");
            _requestLog=new NullRequestLog();
        }
        super.doStart();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_requestLog instanceof NullRequestLog)
            _requestLog=null;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class NullRequestLog extends AbstractLifeCycle implements RequestLog
    {
        @Override
        public void log(Request request, Response response)
        {            
        }
    }
}
