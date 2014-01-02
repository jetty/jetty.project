//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;

/**
 * Process incoming AddChannelRequest headers within the existing Jetty framework. Benefiting from Server container knowledge and various webapp configuration
 * knowledge.
 */
public class HttpChannelOverMux extends HttpChannel<ByteBuffer>
{
    public HttpChannelOverMux(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
    {
        super(connector,configuration,endPoint,transport,input);
    }
}
