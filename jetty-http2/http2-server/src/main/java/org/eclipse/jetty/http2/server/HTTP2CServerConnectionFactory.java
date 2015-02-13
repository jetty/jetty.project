//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;

public class HTTP2CServerConnectionFactory extends HTTP2ServerConnectionFactory
{
    public HTTP2CServerConnectionFactory(HttpConfiguration httpConfiguration)
    {
        super(httpConfiguration,"h2c");
    }
    
    @Override
    public boolean isAcceptable(String protocol, String tlsProtocol, String tlsCipher)
    {
        // Never use TLS with h2c
        return false;
    }
    
    protected ServerParser newServerParser(Connector connector, ServerParser.Listener listener)
    {
        ServerParser parser = super.newServerParser(connector,listener);
        
        if (connector.getDefaultConnectionFactory() instanceof HttpConnectionFactory)
        {
            // This must be a sneaky upgrade from HTTP/1
            // So advance the parsers pointer until after the PRI * HTTP/2.0 request.
            parser.directUpgrade();
        }
        
        return parser;
    }
}
