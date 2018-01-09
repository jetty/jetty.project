//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

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
        WebSocketExtensionRegistry extensionFactory = new WebSocketExtensionRegistry();
        UnitExtensionStack extensionStack = new UnitExtensionStack(extensionFactory);
        extensionStack.setPolicy(policy);
        return extensionStack;
    }
    
    private UnitExtensionStack(WebSocketExtensionRegistry extensionRegistry)
    {
        super(extensionRegistry);
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
            callback.succeeded();
        });
        
        for (WebSocketFrame frame : framesQueue)
        {
            incomingFrame(frame, Callback.NOOP);
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
            callback.succeeded();
        });
        
        for (WebSocketFrame frame : frames)
        {
            outgoingFrame(frame, Callback.NOOP, BatchMode.OFF);
        }
        
        setNextOutgoing(null);
        return captured;
    }
}
