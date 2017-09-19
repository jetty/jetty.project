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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WSBehavior;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WSExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class UnitExtensionStack extends ExtensionStack
{
    public static UnitExtensionStack clientBased()
    {
        return policyBased(new WSPolicy(WSBehavior.CLIENT));
    }
    
    public static UnitExtensionStack serverBased()
    {
        return policyBased(new WSPolicy(WSBehavior.SERVER));
    }

    private static UnitExtensionStack policyBased(WSPolicy policy)
    {
        WSExtensionRegistry extensionFactory = new WSExtensionRegistry();
        UnitExtensionStack extensionStack = new UnitExtensionStack(extensionFactory);
        extensionStack.setPolicy(policy);
        return extensionStack;
    }
    
    private UnitExtensionStack(WSExtensionRegistry extensionRegistry)
    {
        super(extensionRegistry);
    }
    
    /**
     * Process frames
     */
    public BlockingQueue<WSFrame> processIncoming(BlockingQueue<WSFrame> framesQueue)
    {
        BlockingQueue<WSFrame> processed = new LinkedBlockingDeque<>();
        setNextIncoming((frame, callback) ->
        {
            processed.offer(WSFrame.copy(frame));
            callback.succeeded();
        });
        
        for (WSFrame frame : framesQueue)
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
    public List<WSFrame> processOutgoing(List<WSFrame> frames)
    {
        List<WSFrame> captured = new ArrayList<>();
        setNextOutgoing((frame, callback, batchMode) ->
        {
            captured.add(WSFrame.copy(frame));
            callback.succeeded();
        });
        
        for (WSFrame frame : frames)
        {
            outgoingFrame(frame, Callback.NOOP, BatchMode.OFF);
        }
        
        setNextOutgoing(null);
        return captured;
    }
}
