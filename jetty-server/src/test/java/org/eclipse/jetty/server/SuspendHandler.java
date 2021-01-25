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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.HandlerWrapper;

class SuspendHandler extends HandlerWrapper implements AsyncListener
{
    private int _read;
    private long _suspendFor = -1;
    private long _resumeAfter = -1;
    private long _completeAfter = -1;

    public SuspendHandler()
    {
    }

    public int getRead()
    {
        return _read;
    }

    public void setRead(int read)
    {
        _read = read;
    }

    public long getSuspendFor()
    {
        return _suspendFor;
    }

    public void setSuspendFor(long suspendFor)
    {
        _suspendFor = suspendFor;
    }

    public long getResumeAfter()
    {
        return _resumeAfter;
    }

    public void setResumeAfter(long resumeAfter)
    {
        _resumeAfter = resumeAfter;
    }

    public long getCompleteAfter()
    {
        return _completeAfter;
    }

    public void setCompleteAfter(long completeAfter)
    {
        _completeAfter = completeAfter;
    }

    @Override
    public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
    {
        if (DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
        {
            if (_read > 0)
            {
                byte[] buf = new byte[_read];
                request.getInputStream().read(buf);
            }
            else if (_read < 0)
            {
                InputStream in = request.getInputStream();
                int b = in.read();
                while (b != -1)
                {
                    b = in.read();
                }
            }

            final AsyncContext asyncContext = baseRequest.startAsync();
            asyncContext.addListener(this);
            if (_suspendFor > 0)
                asyncContext.setTimeout(_suspendFor);

            if (_completeAfter > 0)
            {
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(_completeAfter);
                            response.getOutputStream().println("COMPLETED");
                            response.setStatus(200);
                            baseRequest.setHandled(true);
                            asyncContext.complete();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }.start();
            }
            else if (_completeAfter == 0)
            {
                response.getOutputStream().println("COMPLETED");
                response.setStatus(200);
                baseRequest.setHandled(true);
                asyncContext.complete();
            }

            if (_resumeAfter > 0)
            {
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(_resumeAfter);
                            asyncContext.dispatch();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }.start();
            }
            else if (_resumeAfter == 0)
            {
                asyncContext.dispatch();
            }
        }
        else if (request.getAttribute("TIMEOUT") != null)
        {
            response.setStatus(200);
            response.getOutputStream().print("TIMEOUT");
            baseRequest.setHandled(true);
        }
        else
        {
            response.setStatus(200);
            response.getOutputStream().print("RESUMED");
            baseRequest.setHandled(true);
        }
    }

    @Override
    public void onComplete(AsyncEvent asyncEvent) throws IOException
    {
    }

    @Override
    public void onTimeout(AsyncEvent asyncEvent) throws IOException
    {
        asyncEvent.getSuppliedRequest().setAttribute("TIMEOUT", Boolean.TRUE);
        asyncEvent.getAsyncContext().dispatch();
    }

    @Override
    public void onError(AsyncEvent asyncEvent) throws IOException
    {
    }

    @Override
    public void onStartAsync(AsyncEvent asyncEvent) throws IOException
    {
    }
}
