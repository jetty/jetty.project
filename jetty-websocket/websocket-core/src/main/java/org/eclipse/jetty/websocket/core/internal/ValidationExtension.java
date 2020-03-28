//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.util.Map;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.websocket.core.OpCode.CONTINUATION;
import static org.eclipse.jetty.websocket.core.OpCode.TEXT;
import static org.eclipse.jetty.websocket.core.OpCode.UNDEFINED;

public class ValidationExtension extends AbstractExtension
{
    private static final Logger LOG = LoggerFactory.getLogger(ValidationExtension.class);

    private WebSocketCoreSession coreSession;
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
    public void setCoreSession(CoreSession coreSession)
    {
        super.setCoreSession(coreSession);

        // TODO: change validation to use static methods instead of down casting CoreSession.
        if (!(coreSession instanceof WebSocketCoreSession))
            throw new IllegalArgumentException("ValidationExtension needs a CoreSession Configuration");
        this.coreSession = (WebSocketCoreSession)coreSession;
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        try
        {
            if (incomingSequence != null)
                incomingSequence.check(frame.getOpCode(), frame.isFin());

            if (incomingFrameValidation)
                coreSession.assertValidIncoming(frame);

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
                coreSession.assertValidOutgoing(frame);

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
