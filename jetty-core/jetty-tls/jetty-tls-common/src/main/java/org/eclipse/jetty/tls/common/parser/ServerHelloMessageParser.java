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
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ServerHelloMessage;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.ext.Extension;

public class ServerHelloMessageParser implements MessageParser
{
    private final ExtensionsParser extensionsParser;
    private State state = State.VERSION;
    private int cursor;
    private int value;
    private int version;
    private byte[] random;
    private byte[] sessionId;
    private CipherSuite cipherSuite;

    public ServerHelloMessageParser(ExtensionsParser extensionsParser)
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
                case VERSION ->
                {
                    if (remaining > 1)
                    {
                        version = byteBuffer.getShort() & 0xFFFF;
                        if (version != TLSVersion.TLS_1_2.code())
                            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid ServerHello TLS version 0x" + Integer.toHexString(version));
                        version = 0;
                        random = new byte[32];
                        state = State.RANDOM;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.VERSION_BYTES;
                    }
                }
                case VERSION_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    version += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (version != TLSVersion.TLS_1_2.code())
                            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid ServerHello TLS version 0x" + Integer.toHexString(version));
                        version = 0;
                        random = new byte[32];
                        state = State.RANDOM;
                    }
                }
                case RANDOM ->
                {
                    int offset = cursor;
                    int length = Math.min(random.length - cursor, remaining);
                    byteBuffer.get(random, offset, length);
                    cursor += length;
                    if (cursor == random.length)
                    {
                        cursor = 0;
                        state = State.SESSION_ID_LENGTH;
                    }
                }
                case SESSION_ID_LENGTH ->
                {
                    value = byteBuffer.get() & 0xFF;
                    sessionId = new byte[value];
                    state = State.SESSION_ID;
                }
                case SESSION_ID ->
                {
                    int offset = cursor;
                    int length = Math.min(value - cursor, remaining);
                    byteBuffer.get(sessionId, offset, length);
                    cursor += length;
                    if (cursor == value)
                    {
                        cursor = 0;
                        value = 0;
                        state = State.CIPHER_SUITE;
                    }
                }
                case CIPHER_SUITE ->
                {
                    if (remaining > 1)
                    {
                        int cipher = byteBuffer.getShort() & 0xFFFF;
                        cipherSuite = CipherSuite.from(cipher);
                        state = State.COMPRESSION_METHOD;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.CIPHER_SUITE_BYTES;
                    }
                }
                case CIPHER_SUITE_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    value += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        cipherSuite = CipherSuite.from(value);
                        value = 0;
                        state = State.COMPRESSION_METHOD;
                    }
                }
                case COMPRESSION_METHOD ->
                {
                    // Skip the compression method.
                    int compressionMethod = byteBuffer.get() & 0xFF;
                    if (compressionMethod != 0)
                        throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid compression method " + compressionMethod);
                    state = State.EXTENSIONS;
                }
                case EXTENSIONS ->
                {
                    List<Extension> extensions = extensionsParser.parse(buffer);
                    if (extensions == null)
                        return null;
                    ServerHelloMessage message = new ServerHelloMessage(random, sessionId, cipherSuite, extensions);
                    random = null;
                    sessionId = null;
                    cipherSuite = null;
                    state = State.VERSION;
                    return message;
                }
            }
        }
    }

    private enum State
    {
        VERSION,
        VERSION_BYTES,
        RANDOM,
        SESSION_ID_LENGTH,
        SESSION_ID,
        CIPHER_SUITE,
        CIPHER_SUITE_BYTES,
        COMPRESSION_METHOD,
        EXTENSIONS
    }
}
