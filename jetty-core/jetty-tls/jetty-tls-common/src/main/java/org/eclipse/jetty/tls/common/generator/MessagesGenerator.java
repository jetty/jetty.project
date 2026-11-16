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

import java.util.EnumMap;
import java.util.Map;

import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;

public class MessagesGenerator
{
    private final Map<Message.Type, MessageGenerator> generators = new EnumMap<>(Message.Type.class);
    private final ExtensionsGenerator extensionsGenerator;

    public MessagesGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, boolean client)
    {
        extensionsGenerator = new ExtensionsGenerator(byteBufferPool, useDirectBuffers, client);
        generators.put(Message.Type.CLIENT_HELLO, new ClientHelloMessageGenerator(byteBufferPool, useDirectBuffers, extensionsGenerator));
        generators.put(Message.Type.SERVER_HELLO, new ServerHelloMessageGenerator(byteBufferPool, useDirectBuffers, extensionsGenerator));
        generators.put(Message.Type.ENCRYPTED_EXTENSIONS, new EncryptedExtensionsMessageGenerator(byteBufferPool, useDirectBuffers, extensionsGenerator));
        generators.put(Message.Type.CERTIFICATE_REQUEST, new CertificateRequestMessageGenerator(byteBufferPool, useDirectBuffers, extensionsGenerator));
        generators.put(Message.Type.CERTIFICATE, new CertificateMessageGenerator(byteBufferPool, useDirectBuffers, extensionsGenerator));
        generators.put(Message.Type.CERTIFICATE_VERIFY, new CertificateVerifyMessageGenerator(byteBufferPool, useDirectBuffers));
        generators.put(Message.Type.FINISHED, new FinishedMessageGenerator(byteBufferPool, useDirectBuffers));
        generators.put(Message.Type.NEW_SESSION_TICKET, new NewSessionTicketMessageGenerator(byteBufferPool, useDirectBuffers, extensionsGenerator));
    }

    public ExtensionsGenerator getExtensionsGenerator()
    {
        return extensionsGenerator;
    }

    public void addListener(MessageGenerator.Listener listener)
    {
        generators.values().forEach(g -> g.addListener(listener));
    }

    public void generate(BufferAccumulator accumulator, Message message) throws Exception
    {
        MessageGenerator messageGenerator = generators.get(message.type());
        if (messageGenerator == null)
            throw new UnsupportedOperationException("could not generate unsupported TLS message " + message);
        messageGenerator.generate(accumulator, message);
    }
}
