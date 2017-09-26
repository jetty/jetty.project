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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

public abstract class AbstractJavaxWebSocketLocalEndpointTest
{
    protected static WebSocketPolicy clientPolicy = WebSocketPolicy.newClientPolicy();
    protected static JavaxWebSocketContainer container;
    
    @BeforeClass
    public static void initContainer() throws Exception
    {
        container = new DummyContainer(clientPolicy);
        container.start();
    }
    
    @AfterClass
    public static void stopContainer() throws Exception
    {
        container.stop();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    protected AvailableEncoders encoders;
    protected AvailableDecoders decoders;
    protected Map<String, String> uriParams = new HashMap<>();
    protected EndpointConfig endpointConfig;
    
    public AbstractJavaxWebSocketLocalEndpointTest()
    {
        endpointConfig = new BasicEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }
    
    public JavaxWebSocketSession newSession()
    {
        String id = this.getClass().getSimpleName();
        URI requestURI = URI.create("ws://localhost/" + id);
        DummyConnection connection = DummyConnection.from(container, requestURI);
        return new JavaxWebSocketSession(container, connection);
    }
}
