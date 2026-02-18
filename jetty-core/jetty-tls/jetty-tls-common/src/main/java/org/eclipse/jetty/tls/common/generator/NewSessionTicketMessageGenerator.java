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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NewSessionTicketMessage;

public class NewSessionTicketMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public NewSessionTicketMessageGenerator(ByteBufferPool byteBufferPool, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message)
    {
        generate(accumulator, (NewSessionTicketMessage)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, NewSessionTicketMessage message)
    {
        byte[] nonce = message.nonce();
        byte[] ticket = message.ticket();

        RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(getByteBufferPool(), true, -1, 0, 0);
        int extensionsLength = extensionsGenerator.generate(extensionsAccumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate NewSessionTicket, extensions too long");

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

        int length = 4 + 4 + 1 + nonce.length + 2 + ticket.length + 2 + extensionsLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate NewSessionTicket, too long");

        int typeAndLength = (message.type().code() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.putInt((int)message.lifetime());
        accumulator.putInt(message.ageAdd());

        accumulator.put((byte)nonce.length);
        accumulator.put(nonce);

        accumulator.putShort((short)ticket.length);
        accumulator.put(ticket);

        accumulator.putShort((short)extensionsLength);
        accumulator.add(extensionsAccumulator);

        notifyMessageGenerated(message);
    }
}