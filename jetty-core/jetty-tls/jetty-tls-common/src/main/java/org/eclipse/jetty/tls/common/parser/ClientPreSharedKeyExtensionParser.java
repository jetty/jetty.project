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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.ext.ClientPreSharedKeyExtension;
import org.eclipse.jetty.tls.ext.PreSharedKeyIdentity;

public class ClientPreSharedKeyExtensionParser implements ExtensionParser
{
    private final List<PreSharedKeyIdentity> identities = new ArrayList<>();
    private final List<byte[]> binders = new ArrayList<>();
    private final Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int identitiesLength;
    private int identityLength;
    private byte[] identity;
    private int cursor;
    private int obfuscatedTicketAge;
    private int bindersLength;
    private int binderLength;
    private byte[] binder;

    public ClientPreSharedKeyExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return ClientPreSharedKeyExtension.CODE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = byteBuffer.getShort() & 0xFFFF;
                        state = State.IDENTITIES_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.IDENTITIES_LENGTH;
                }
                case IDENTITIES_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        identitiesLength = byteBuffer.getShort() & 0xFFFF;
                        state = State.IDENTITY_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.IDENTITIES_LENGTH_BYTES;
                    }
                }
                case IDENTITIES_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    identitiesLength += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.IDENTITY_LENGTH;
                }
                case IDENTITY_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        identityLength = byteBuffer.getShort() & 0xFFFF;
                        identitiesLength -= 2;
                        identity = new byte[identityLength];
                        state = State.IDENTITY;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.IDENTITY_LENGTH_BYTES;
                    }
                }
                case IDENTITY_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    identityLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        identitiesLength -= 2;
                        identity = new byte[identityLength];
                        state = State.IDENTITY;
                    }
                }
                case IDENTITY ->
                {
                    int offset = identity.length - identityLength;
                    int length = Math.min(identityLength, remaining);
                    byteBuffer.get(identity, offset, length);
                    identityLength -= length;
                    if (identityLength == 0)
                    {
                        identitiesLength -= identity.length;
                        state = State.OBFUSCATED_TICKET_AGE;
                    }
                }
                case OBFUSCATED_TICKET_AGE ->
                {
                    if (remaining > 3)
                    {
                        obfuscatedTicketAge = byteBuffer.getInt();
                        identities.add(new PreSharedKeyIdentity(identity, obfuscatedTicketAge));
                        identity = null;
                        obfuscatedTicketAge = 0;
                        identitiesLength -= 4;
                        state = identitiesLength == 0 ? State.BINDERS_LENGTH : State.IDENTITY_LENGTH;
                    }
                    else
                    {
                        cursor = 4;
                        state = State.OBFUSCATED_TICKET_AGE_BYTES;
                    }
                }
                case OBFUSCATED_TICKET_AGE_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    obfuscatedTicketAge += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        identities.add(new PreSharedKeyIdentity(identity, obfuscatedTicketAge));
                        identity = null;
                        obfuscatedTicketAge = 0;
                        identitiesLength -= 4;
                        state = identitiesLength == 0 ? State.BINDERS_LENGTH : State.IDENTITY_LENGTH;
                    }
                }
                case BINDERS_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        bindersLength = byteBuffer.getShort() & 0xFFFF;
                        state = State.BINDER_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.BINDERS_LENGTH_BYTES;
                    }
                }
                case BINDERS_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    bindersLength += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.BINDER_LENGTH;
                }
                case BINDER_LENGTH ->
                {
                    binderLength = byteBuffer.get() & 0xFF;
                    bindersLength -= 1;
                    binder = new byte[binderLength];
                    state = State.BINDER;
                }
                case BINDER ->
                {
                    int offset = binder.length - binderLength;
                    int length = Math.min(binderLength, remaining);
                    byteBuffer.get(binder, offset, length);
                    binderLength -= length;
                    if (binderLength == 0)
                    {
                        binders.add(binder);
                        bindersLength -= binder.length;
                        binder = null;
                        if (bindersLength == 0)
                        {
                            if (identities.size() != binders.size())
                                throw new IllegalStateException("invalid number of identities and binders");
                            ClientPreSharedKeyExtension extension = new ClientPreSharedKeyExtension(List.copyOf(identities), List.copyOf(binders));
                            identities.clear();
                            binders.clear();
                            state = State.TOTAL_LENGTH;
                            int result = 2 + totalLength;
                            totalLength = 0;
                            listener.onExtension(extension);
                            return result;
                        }
                        else
                        {
                            state = State.BINDER_LENGTH;
                        }
                    }
                }
            }
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        IDENTITIES_LENGTH,
        IDENTITIES_LENGTH_BYTES,
        IDENTITY_LENGTH,
        IDENTITY_LENGTH_BYTES,
        IDENTITY,
        OBFUSCATED_TICKET_AGE,
        OBFUSCATED_TICKET_AGE_BYTES,
        BINDERS_LENGTH,
        BINDERS_LENGTH_BYTES,
        BINDER_LENGTH,
        BINDER
    }
}
