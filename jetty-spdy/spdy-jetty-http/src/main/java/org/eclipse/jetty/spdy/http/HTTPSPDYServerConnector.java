//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================


package org.eclipse.jetty.spdy.http;

import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.server.HttpServerConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;

public class HTTPSPDYServerConnector extends SPDYServerConnector
{
    public HTTPSPDYServerConnector(Server server)
    {
        this(server, Collections.<Short, PushStrategy>emptyMap());
    }

    public HTTPSPDYServerConnector(Server server, Map<Short, PushStrategy> pushStrategies)
    {
        // We pass a null ServerSessionFrameListener because for
        // HTTP over SPDY we need one that references the endPoint
        super(server, null);
        clearConnectionFactories();
        // The "spdy/3" protocol handles HTTP over SPDY
        putConnectionFactory("spdy/3", new ServerHTTPSPDYAsyncConnectionFactory(SPDY.V3, getByteBufferPool(), getExecutor(), getScheduler(), this, getPushStrategy(SPDY.V3, pushStrategies)));
        // The "spdy/2" protocol handles HTTP over SPDY
        putConnectionFactory("spdy/2", new ServerHTTPSPDYAsyncConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), this, getPushStrategy(SPDY.V2, pushStrategies)));
        // The "http/1.1" protocol handles browsers that support NPN but not SPDY
        putConnectionFactory("http/1.1", new HttpServerConnectionFactory(this));
        // The default connection factory handles plain HTTP on non-SSL or non-NPN connections
        setDefaultConnectionFactory(getConnectionFactory("http/1.1"));
    }

    private PushStrategy getPushStrategy(short version, Map<Short, PushStrategy> pushStrategies)
    {
        PushStrategy pushStrategy = pushStrategies.get(version);
        if(pushStrategy == null)
            pushStrategy = new PushStrategy.None();
        return pushStrategy;
    }

}
