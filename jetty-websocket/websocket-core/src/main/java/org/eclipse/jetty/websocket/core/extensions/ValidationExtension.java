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

package org.eclipse.jetty.websocket.core.extensions;

import java.util.Map;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.BatchMode;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

public class ValidationExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(ValidationExtension.class);

    private OpCode.Sequence incomingSequence = null;
    private OpCode.Sequence outgoingSequence = null;
    private boolean incomingFrameValidation = false;
    private boolean outgoingFrameValidation = false;

    @Override
    public String getName()
    {
        return "@validation";
    }

    @Override
    public void onReceiveFrame(Frame frame, Callback callback)
    {
        try
        {
            if (incomingSequence != null)
                incomingSequence.check(frame.getOpCode(), frame.isFin());

            if (incomingFrameValidation)
                getWebSocketChannel().assertValidIncoming(frame);

            nextIncomingFrame(frame, callback);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        try
        {
            if (outgoingSequence != null)
                outgoingSequence.check(frame.getOpCode(), frame.isFin());

            if (outgoingFrameValidation)
                getWebSocketChannel().assertValidOutgoing(frame);

            nextOutgoingFrame(frame, callback, batchMode);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    @Override
    public void init(ExtensionConfig config, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super.init(config, policy, bufferPool);

        Map<String, String> parameters = config.getParameters();

        if (parameters.containsKey("outgoing-sequence"))
            outgoingSequence = new OpCode.Sequence();

        if (parameters.containsKey("incoming-sequence"))
            incomingSequence = new OpCode.Sequence();

        if (parameters.containsKey("incoming-frame"))
            incomingFrameValidation = true;

        if (parameters.containsKey("outgoing-frame"))
            outgoingFrameValidation = true;
    }
}
