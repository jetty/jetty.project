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

package org.eclipse.jetty.websocket.jsr356.samples;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Base Abstract Class.
 */
public abstract class AbstractStringEndpoint extends Endpoint implements MessageHandler.Whole<String>
{
    private static final Logger LOG = Log.getLogger(AbstractStringEndpoint.class);
    protected Session session;
    protected EndpointConfig config;

    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen({}, {})", session, config);
        session.addMessageHandler(this);
        this.session = session;
        this.config = config;
    }

    @Override
    public void onClose(Session session, CloseReason closeReason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose({}, {})", session, closeReason);
        this.session = null;
    }

    @Override
    public void onError(Session session, Throwable thr)
    {
        LOG.warn("onError()", thr);
    }
}
