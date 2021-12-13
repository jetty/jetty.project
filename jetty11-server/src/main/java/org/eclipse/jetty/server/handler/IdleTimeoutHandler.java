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

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

/**
 * Handler to adjust the idle timeout of requests while dispatched.
 * Can be applied in jetty.xml with
 * <pre>
 *   &lt;Get id='handler' name='Handler'/&gt;
 *   &lt;Set name='Handler'&gt;
 *     &lt;New id='idleTimeoutHandler' class='org.eclipse.jetty.server.handler.IdleTimeoutHandler'&gt;
 *       &lt;Set name='Handler'&gt;&lt;Ref id='handler'/&gt;&lt;/Set&gt;
 *       &lt;Set name='IdleTimeoutMs'&gt;5000&lt;/Set&gt;
 *     &lt;/New&gt;
 *   &lt;/Set&gt;
 * </pre>
 */
public class IdleTimeoutHandler extends HandlerWrapper
{
    private long _idleTimeoutMs = 1000;
    private boolean _applyToAsync = false;

    public boolean isApplyToAsync()
    {
        return _applyToAsync;
    }

    /**
     * Should the adjusted idle time be maintained for asynchronous requests
     *
     * @param applyToAsync true if alternate idle timeout is applied to asynchronous requests
     */
    public void setApplyToAsync(boolean applyToAsync)
    {
        _applyToAsync = applyToAsync;
    }

    public long getIdleTimeoutMs()
    {
        return _idleTimeoutMs;
    }

    /**
     * @param idleTimeoutMs The idle timeout in MS to apply while dispatched or async
     */
    public void setIdleTimeoutMs(long idleTimeoutMs)
    {
        this._idleTimeoutMs = idleTimeoutMs;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final HttpChannel channel = baseRequest.getHttpChannel();
        final long idle_timeout = baseRequest.getHttpChannel().getIdleTimeout();
        channel.setIdleTimeout(_idleTimeoutMs);

        try
        {
            super.handle(target, baseRequest, request, response);
        }
        finally
        {
            if (_applyToAsync && request.isAsyncStarted())
            {
                request.getAsyncContext().addListener(new AsyncListener()
                {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        channel.setIdleTimeout(idle_timeout);
                    }

                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                        channel.setIdleTimeout(idle_timeout);
                    }
                });
            }
            else
                channel.setIdleTimeout(idle_timeout);
        }
    }
}
