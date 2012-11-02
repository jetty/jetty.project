//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.websocket.core.api.UpgradeRequest;
import org.eclipse.jetty.websocket.core.api.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.InternalConnection;

public class UpgradeContext
{
    private InternalConnection connection;
    private UpgradeRequest request;
    private UpgradeResponse response;

    public InternalConnection getConnection()
    {
        return connection;
    }

    public UpgradeRequest getRequest()
    {
        return request;
    }

    public UpgradeResponse getResponse()
    {
        return response;
    }

    public void setConnection(InternalConnection connection)
    {
        this.connection = connection;
    }

    public void setRequest(UpgradeRequest request)
    {
        this.request = request;
    }

    public void setResponse(UpgradeResponse response)
    {
        this.response = response;
    }
}
