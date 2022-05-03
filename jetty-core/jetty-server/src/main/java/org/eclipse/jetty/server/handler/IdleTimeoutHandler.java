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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

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
public class IdleTimeoutHandler extends Handler.Wrapper
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
    public Request.Processor handle(Request request) throws Exception
    {
        Request.Processor processor = super.handle(request);
        if (processor == null)
            return null;

        return (rq, rs, cb) ->
        {
            long idleTimeout = 0; // TODO rq.getHttpChannel().getIdleTimeout();
            // TODO rq.getHttpChannel().setIdleTimeout(_idleTimeoutMs);
            processor.process(rq, rs, Callback.from(cb, () ->
            {
                // TODO rq.getHttpChannel().setIdleTimeout(idleTimeout)
            }));
        };
    }
}
