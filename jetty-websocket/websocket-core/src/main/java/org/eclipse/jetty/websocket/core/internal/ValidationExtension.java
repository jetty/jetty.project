//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.internal;

import java.util.Map;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.NullAppendable;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

import static org.eclipse.jetty.websocket.core.OpCode.CONTINUATION;
import static org.eclipse.jetty.websocket.core.OpCode.TEXT;
import static org.eclipse.jetty.websocket.core.OpCode.UNDEFINED;

public class ValidationExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(ValidationExtension.class);

    private FrameSequence incomingSequence = null;
    private FrameSequence outgoingSequence = null;
    private boolean incomingFrameValidation = false;
    private boolean outgoingFrameValidation = false;
    private NullAppendable incomingUtf8Validation = null;
    private NullAppendable outgoingUtf8Validation = null;
    private byte continuedOutOpCode = UNDEFINED;
    private byte continuedInOpCode = UNDEFINED;

    @Override
    public String getName()
    {
        return "@validation";
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        try
        {
            if (incomingSequence != null)
                incomingSequence.check(frame.getOpCode(), frame.isFin());

            if (incomingFrameValidation)
                getWebSocketCoreSession().assertValidIncoming(frame);

            if (incomingUtf8Validation != null)
                validateUTF8(frame, incomingUtf8Validation, continuedInOpCode);

            continuedInOpCode = recordLastOpCode(frame, continuedInOpCode);
            nextIncomingFrame(frame, callback);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        try
        {
            if (outgoingSequence != null)
                outgoingSequence.check(frame.getOpCode(), frame.isFin());

            if (outgoingFrameValidation)
                getWebSocketCoreSession().assertValidOutgoing(frame);

            if (outgoingUtf8Validation != null)
                validateUTF8(frame, outgoingUtf8Validation, continuedOutOpCode);

            continuedOutOpCode = recordLastOpCode(frame, continuedOutOpCode);
            nextOutgoingFrame(frame, callback, batch);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    @Override
    public void init(ExtensionConfig config, WebSocketComponents components)
    {
        super.init(config, components);

        Map<String, String> parameters = config.getParameters();

        if (parameters.containsKey("outgoing-sequence"))
            outgoingSequence = new FrameSequence();

        if (parameters.containsKey("incoming-sequence"))
            incomingSequence = new FrameSequence();

        if (parameters.containsKey("incoming-frame"))
            incomingFrameValidation = true;

        if (parameters.containsKey("outgoing-frame"))
            outgoingFrameValidation = true;

        if (parameters.containsKey("incoming-utf8"))
            incomingUtf8Validation = new NullAppendable();

        if (parameters.containsKey("outgoing-utf8"))
            outgoingUtf8Validation = new NullAppendable();
    }

    private void validateUTF8(Frame frame, NullAppendable appendable, byte continuedOpCode)
    {
        //TODO this relies on sequencing being set

        if (frame.isControlFrame())
        {
            //todo validate utf8 of control frames

        }
        else
        {
            if (frame.getOpCode() == TEXT || continuedOpCode == TEXT)
            {
                if (frame.hasPayload())
                    appendable.append(frame.getPayload().slice());

                if (frame.isFin())
                    appendable.checkState();
            }
        }
    }

    public byte recordLastOpCode(Frame frame, byte prevOpcode) throws ProtocolException
    {
        byte opcode = prevOpcode;
        boolean fin = frame.isFin();

        if (fin)
            opcode = UNDEFINED;
        else if (opcode != CONTINUATION)
            opcode = frame.getOpCode();

        return opcode;
    }
}
