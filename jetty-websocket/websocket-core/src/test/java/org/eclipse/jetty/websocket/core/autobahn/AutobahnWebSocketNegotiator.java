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

package org.eclipse.jetty.websocket.core.autobahn;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

class AutobahnWebSocketNegotiator implements WebSocketNegotiator
{
    final DecoratedObjectFactory objectFactory;
    final WebSocketExtensionRegistry extensionRegistry;
    final ByteBufferPool bufferPool;

    public AutobahnWebSocketNegotiator(DecoratedObjectFactory objectFactory, WebSocketExtensionRegistry extensionRegistry, ByteBufferPool bufferPool)
    {
        this.objectFactory = objectFactory;
        this.extensionRegistry = extensionRegistry;
        this.bufferPool = bufferPool;
    }

    @Override
    public FrameHandler negotiate(Negotiation negotiation) throws IOException
    {
        // Finalize negotiations in API layer involves:
        // TODO need access to real request/response????
        //  + MAY mutate the policy
        //  + MAY replace the policy
        //  + MAY read request and set response headers
        //  + MAY reject with sendError semantics
        //  + MAY change/add/remove offered extensions 
        //  + MUST pick subprotocol
        //  + MUST return the FrameHandler or null or exception?
        
        // Examples of those steps are below:

        //  + MAY mutate the policy
        WebSocketPolicy policy = negotiation.getPolicy();
        policy.setIdleTimeout(policy.getIdleTimeout()+1);
        int bigFrameSize = 20 * 1024 * 1024;
        policy.setMaxBinaryMessageSize(bigFrameSize);
        policy.setMaxTextMessageSize(bigFrameSize);
        policy.setIdleTimeout(5000);
        //  + MAY replace the policy
        negotiation.setPolicy(policy.clonePolicy());
        
        //  + MAY read request and set response headers
        String special = negotiation.getRequest().getHeader("MySpecialHeader");
        if (special!=null)
            negotiation.getResponse().setHeader("MySpecialHeader","OK:"+special);
        
        //  + MAY reject with sendError semantics
        if ("abort".equals(special))
        {
            negotiation.getResponse().sendError(401,"Some Auth reason");
            return null;
        }
            
        //  + MAY change extensions by mutating response headers
        List<ExtensionConfig> offeredExtensions = negotiation.getOfferedExtensions();
        // negotiateResponse.addHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),"@identity");
        
        //  + MUST pick subprotocol
        List<String> subprotocols = negotiation.getOfferedSubprotocols();
        String subprotocol = (subprotocols==null || subprotocols.isEmpty())?null:subprotocols.get(0);
        negotiation.setSubprotocol(subprotocol);

        //  + MUST return the FrameHandler or null or exception?
        return new AutobahnFrameHandler();
    }

    @Override
    public WebSocketPolicy getCandidatePolicy()
    {
        return null;
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

}
