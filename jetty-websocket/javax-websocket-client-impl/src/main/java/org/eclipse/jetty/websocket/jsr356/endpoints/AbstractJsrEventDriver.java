//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.util.Map;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.AbstractEventDriver;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;

public abstract class AbstractJsrEventDriver extends AbstractEventDriver
{
    protected final EndpointMetadata metadata;
    protected final EndpointConfig config;
    protected JsrSession jsrsession;
    private boolean hasCloseBeenCalled = false;

    public AbstractJsrEventDriver(WebSocketPolicy policy, EndpointInstance endpointInstance)
    {
        super(policy,endpointInstance.getEndpoint());
        this.config = endpointInstance.getConfig();
        this.metadata = endpointInstance.getMetadata();
    }

    public EndpointConfig getConfig()
    {
        return config;
    }

    public Session getJsrSession()
    {
        return this.jsrsession;
    }

    public EndpointMetadata getMetadata()
    {
        return metadata;
    }

    public abstract void init(JsrSession jsrsession);

    @Override
    public final void onClose(CloseInfo close)
    {
        if (hasCloseBeenCalled)
        {
            // avoid duplicate close events (possible when using harsh Session.disconnect())
            return;
        }
        hasCloseBeenCalled = true;

        CloseCode closecode = CloseCodes.getCloseCode(close.getStatusCode());
        CloseReason closereason = new CloseReason(closecode,close.getReason());
        onClose(closereason);
    }

    protected abstract void onClose(CloseReason closereason);

    @Override
    public void onFrame(Frame frame)
    {
        /* Ignored, not supported by JSR-356 */
    }

    @Override
    public final void openSession(WebSocketSession session)
    {
        // Cast should be safe, as it was created by JsrSessionFactory
        this.jsrsession = (JsrSession)session;

        // Allow jsr session to init
        this.jsrsession.init(config);

        // Allow event driver to init itself
        init(jsrsession);

        // Allow end-user socket to adjust configuration
        super.openSession(session);
    }

    public void setEndpointconfig(EndpointConfig endpointconfig)
    {
        throw new RuntimeException("Why are you reconfiguring the endpoint?");
        // this.config = endpointconfig;
    }

    public abstract void setPathParameters(Map<String, String> pathParameters);
}
