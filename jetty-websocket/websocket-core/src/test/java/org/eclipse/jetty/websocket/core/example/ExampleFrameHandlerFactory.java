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

package org.eclipse.jetty.websocket.core.example;

import java.util.List;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.NegotiateMessage.Request;
import org.eclipse.jetty.websocket.core.NegotiateMessage.Response;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;

class ExampleFrameHandlerFactory implements FrameHandlerFactory
{
    DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
    WebSocketExtensionRegistry extensionRegistry = new WebSocketExtensionRegistry();

    @Override
    public FrameHandler newFrameHandler(Request negotiateRequest, Response negotiateResponse, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {        
        // Finalize negotiations in API layer involves:
        //  + MAY mutate the policy
        //  + MAY read request and set response headers
        //  + MAY reject with sendError semantics
        //  + MAY change extensions by mutating response headers
        //  + MUST pick subprotocol
        //  + MUST return the FrameHandler or null or exception?
        
        
        // Examples of those steps are below:

        //  + MAY mutate the policy
        policy.setIdleTimeout(policy.getIdleTimeout()+1);
        
        //  + MAY read request and set response headers
        String special = negotiateRequest.getHeader("MySpecialHeader");
        if (special!=null)
            negotiateResponse.setHeader("MySpecialHeader","OK:"+special);
        
        //  + MAY reject with sendError semantics
        if ("abort".equals(special))
        {
            negotiateResponse.clearHeaders();
            negotiateResponse.sendError(401,"Some Auth reason");
            return null;
        }
            
        //  + MAY change extensions by mutating response headers
        // negotiateResponse.addHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),"@identity");
        List<ExtensionConfig> offeredExtensions = negotiateRequest.getOfferedExtensions();
        ExtensionStack extensionStack = new ExtensionStack(extensionRegistry);
        extensionStack.negotiate(objectFactory, policy, bufferPool, offeredExtensions);
        negotiateResponse.setExtensionStack(extensionStack);
        
        //  + MUST pick subprotocol
        List<String> subprotocols = negotiateRequest.getOfferedSubprotocols();
        String subprotocol = (subprotocols==null || subprotocols.isEmpty())?null:subprotocols.get(0);
        negotiateResponse.setSubprotocol(subprotocol);

        //  + MUST return the FrameHandler or null or exception?
        return new ExampleFrameHandler();
    }

}
