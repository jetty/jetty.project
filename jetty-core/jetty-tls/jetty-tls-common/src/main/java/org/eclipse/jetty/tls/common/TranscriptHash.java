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

package org.eclipse.jetty.tls.common;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.common.generator.MessagesGenerator;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The Transcript Hash defined in
/// [RFC 8446, 4.4.1](https://datatracker.ietf.org/doc/html/rfc8446#section-4.4.1).
///
/// The Transcript Hash keeps a running hash of TLS handshake messages
/// that have been sent, providing a hash used to derive secrets.
public class TranscriptHash
{
    private static final Logger LOG = LoggerFactory.getLogger(TranscriptHash.class);

    private final Queue<Message> messages = new ArrayDeque<>();
    private final MessagesGenerator generator;
    private final RetainableByteBuffer.Mutable accumulator;
    private MessageDigest digest;
    private byte[] emptyHash;
    private byte[] hash;

    public TranscriptHash(MessagesGenerator generator)
    {
        this.generator = generator;
        this.accumulator = new RetainableByteBuffer.DynamicCapacity(generator.getByteBufferPool(), false, -1, 0, 0);
    }

    public void initialize(CipherSuite cipherSuite)
    {
        try
        {
            String algorithm = "SHA-" + (cipherSuite.hashLength() * 8);
            digest = MessageDigest.getInstance(algorithm);
            if (LOG.isDebugEnabled())
                LOG.debug("initialized digest {} from {} on {}", algorithm, cipherSuite, this);
            emptyHash = digest.digest();
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.INTERNAL_ERROR, x);
        }
    }

    public byte[] getEmptyHash()
    {
        return emptyHash;
    }

    public byte[] getHash() throws Exception
    {
        if (hash != null)
            return hash;
        List<Message> transcript = new ArrayList<>(messages);
        accumulator.clear();
        for (Message message : transcript)
        {
            generator.generate(accumulator, message);
        }
        digest.update(accumulator.getByteBuffer());
        hash = digest.digest();
        return hash;
    }

    public void offer(Message message)
    {
        messages.offer(message);
        hash = null;
        if (LOG.isDebugEnabled())
            LOG.debug("offered {} on {}", message, this);
    }

    public void clear()
    {
        messages.clear();
    }

    public void dispose()
    {
        messages.clear();
        accumulator.release();
    }

    @Override
    public String toString()
    {
        return "%s@%x[messages=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), messages.stream().map(Message::type).toList());
    }
}
