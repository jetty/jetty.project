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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;

public class UnitExtensionStack extends ExtensionStack
{
    public static UnitExtensionStack clientBased()
    {
        return policyBased(new WebSocketPolicy(WebSocketBehavior.CLIENT));
    }
    
    public static UnitExtensionStack serverBased()
    {
        return policyBased(new WebSocketPolicy(WebSocketBehavior.SERVER));
    }
    
    private static UnitExtensionStack policyBased(WebSocketPolicy policy)
    {
        SimpleContainerScope containerScope = new SimpleContainerScope(policy);
        WebSocketExtensionFactory extensionFactory = new WebSocketExtensionFactory(containerScope);
        return new UnitExtensionStack(extensionFactory);
    }
    
    private UnitExtensionStack(WebSocketExtensionFactory extensionFactory)
    {
        super(extensionFactory);
    }
    
    /**
     * Process frames
     */
    public BlockingQueue<WebSocketFrame> processIncoming(BlockingQueue<WebSocketFrame> framesQueue)
    {
        BlockingQueue<WebSocketFrame> processed = new LinkedBlockingDeque<>();
        setNextIncoming((frame, callback) ->
        {
            processed.offer(WebSocketFrame.copy(frame));
            callback.succeed();
        });
        
        FrameCallback callback = new FrameCallback.Adapter();
        for (WebSocketFrame frame : framesQueue)
        {
            incomingFrame(frame, callback);
        }
        
        setNextIncoming(null);
        return processed;
    }
    
    /**
     * Process frames as if they are for an outgoing path
     *
     * @param frames the frames to process
     * @return the processed frames (post extension stack)
     */
    public List<WebSocketFrame> processOutgoing(List<WebSocketFrame> frames)
    {
        List<WebSocketFrame> captured = new ArrayList<>();
        setNextOutgoing((frame, callback, batchMode) ->
        {
            captured.add(WebSocketFrame.copy(frame));
            callback.succeed();
        });
        
        FrameCallback callback = new FrameCallback.Adapter();
        for (WebSocketFrame frame : frames)
        {
            outgoingFrame(frame, callback, BatchMode.OFF);
        }
        
        setNextOutgoing(null);
        return captured;
    }
}
