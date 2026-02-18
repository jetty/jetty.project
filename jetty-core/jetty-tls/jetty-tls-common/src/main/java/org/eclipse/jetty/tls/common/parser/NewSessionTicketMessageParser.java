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

package org.eclipse.jetty.tls.common.parser;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NewSessionTicketMessage;
import org.eclipse.jetty.tls.ext.Extension;

public class NewSessionTicketMessageParser implements MessageParser
{
    private final ExtensionsParser extensionsParser;
    private State state = State.LIFETIME;
    private int cursor;
    private long lifetime;
    private int ageAdd;
    private int nonceLength;
    private byte[] nonce;
    private int ticketLength;
    private byte[] ticket;

    public NewSessionTicketMessageParser(ExtensionsParser extensionsParser)
    {
        this.extensionsParser = extensionsParser;
    }

    @Override
    public Message parse(int messageLength, RetainableByteBuffer buffer)
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;
            switch (state)
            {
                case LIFETIME ->
                {
                    if (remaining >= 4)
                    {
                        lifetime = byteBuffer.getInt() & 0xFFFFFFFFL;
                        state = State.AGE_ADD;
                    }
                    else
                    {
                        cursor = 4;
                        state = State.LIFETIME_BYTES;
                    }
                }
                case LIFETIME_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    lifetime += (long)b << (8 * cursor);
                    if (cursor == 0)
                        state = State.AGE_ADD;
                }
                case AGE_ADD ->
                {
                    if (remaining >= 4)
                    {
                        ageAdd = byteBuffer.getInt();
                        state = State.NONCE_LENGTH;
                    }
                    else
                    {
                        cursor = 4;
                        state = State.AGE_ADD_BYTES;
                    }
                }
                case AGE_ADD_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    ageAdd += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.NONCE_LENGTH;
                }
                case NONCE_LENGTH ->
                {
                    nonceLength = byteBuffer.get() & 0xFF;
                    nonce = new byte[nonceLength];
                    state = nonceLength == 0 ? State.TICKET_LENGTH : State.NONCE;
                }
                case NONCE ->
                {
                    int length = Math.min(nonceLength - cursor, remaining);
                    byteBuffer.get(nonce, cursor, length);
                    cursor += length;
                    if (cursor == nonceLength)
                    {
                        cursor = 0;
                        state = State.TICKET_LENGTH;
                    }
                }
                case TICKET_LENGTH ->
                {
                    if (remaining >= 2)
                    {
                        ticketLength = byteBuffer.getShort() & 0xFFFF;
                        ticket = new byte[ticketLength];
                        state = State.TICKET;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TICKET_LENGTH_BYTES;
                    }
                }
                case TICKET_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    ticketLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        ticket = new byte[ticketLength];
                        state = State.TICKET;
                    }
                }
                case TICKET ->
                {
                    int length = Math.min(ticketLength - cursor, remaining);
                    byteBuffer.get(ticket, cursor, length);
                    cursor += length;
                    if (cursor == ticketLength)
                    {
                        cursor = 0;
                        state = State.EXTENSIONS;
                    }
                }
                case EXTENSIONS ->
                {
                    List<Extension> extensions = extensionsParser.parse(buffer);
                    if (extensions == null)
                        return null;
                    NewSessionTicketMessage message = new NewSessionTicketMessage(lifetime, ageAdd, nonce, ticket, extensions);
                    state = State.LIFETIME;
                    cursor = 0;
                    lifetime = 0;
                    ageAdd = 0;
                    nonceLength = 0;
                    nonce = null;
                    ticketLength = 0;
                    ticket = null;
                    return message;
                }
            }
        }
    }

    private enum State
    {
        LIFETIME,
        LIFETIME_BYTES,
        AGE_ADD,
        AGE_ADD_BYTES,
        NONCE_LENGTH,
        NONCE,
        TICKET_LENGTH,
        TICKET_LENGTH_BYTES,
        TICKET,
        EXTENSIONS
    }
}