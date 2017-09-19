//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import org.eclipse.jetty.websocket.common.WSSession;
import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.WSRemoteEndpoint;
import org.eclipse.jetty.websocket.core.io.WSConnection;

public class UntrustedWSSession<T extends WSConnection> extends WSSession<T> implements AutoCloseable
{
    private final UntrustedWSConnection untrustedConnection;
    private UntrustedWSEndpoint untrustedEndpoint;

    public UntrustedWSSession(T connection)
    {
        super(connection);
        connection.getGenerator().setValidating(false);
        this.untrustedConnection = new UntrustedWSConnection(connection);
    }

    @Override
    public void setWebSocketEndpoint(Object endpoint, WSPolicy policy, WSLocalEndpoint localEndpoint, WSRemoteEndpoint remoteEndpoint)
    {
        this.untrustedEndpoint = (UntrustedWSEndpoint) localEndpoint;
        super.setWebSocketEndpoint(endpoint, policy, localEndpoint, remoteEndpoint);
    }

    public UntrustedWSConnection getUntrustedConnection()
    {
        return untrustedConnection;
    }

    public UntrustedWSEndpoint getUntrustedEndpoint()
    {
        return untrustedEndpoint;
    }
}
