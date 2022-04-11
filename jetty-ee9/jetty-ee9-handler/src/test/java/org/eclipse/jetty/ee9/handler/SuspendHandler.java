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

package org.eclipse.jetty.ee9.handler;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
