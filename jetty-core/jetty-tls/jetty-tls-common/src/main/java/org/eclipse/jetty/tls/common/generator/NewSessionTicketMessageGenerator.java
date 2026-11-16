//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tls.common.generator;

import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NewSessionTicketMessage;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class NewSessionTicketMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public NewSessionTicketMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool, useDirectBuffers);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message)
    {
        generate(accumulator, (NewSessionTicketMessage)message);
    }

    private void generate(BufferAccumulator accumulator, NewSessionTicketMessage message)
    {
        // RFC-8446[4.6.1].
        // Field                | (bytes)
        // ---------------------+--------
        // ticket_lifetime      | (4)
        // ticket_age_add       | (4)
        // ticket_nonce length  | (1)
        // ticket_nonce         | (N)
        // ticket length        | (2)
        // ticket               | (T)
        // extensions length    | (2)
        // extensions           | (E)

        byte[] nonce = message.nonce();
        byte[] ticket = message.ticket();
        int capacity = 4 + 4 + 4 + 1 + nonce.length + 2 + ticket.length + 2;
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);

        int extensionsLength = extensionsGenerator.generate(accumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate NewSessionTicket, extensions too long");

        int length = capacity - 4 + extensionsLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate NewSessionTicket, too long");

        int typeAndLength = (message.type().code() << 24) | length;
        header.putInt(typeAndLength);

        header.putInt((int)message.lifetime());
        header.putInt(message.ageAdd());

        header.put((byte)nonce.length);
        header.put(nonce);

        header.putShort((short)ticket.length);
        header.put(ticket);

        header.putShort((short)extensionsLength);

        notifyMessageGenerated(message);
    }
}
