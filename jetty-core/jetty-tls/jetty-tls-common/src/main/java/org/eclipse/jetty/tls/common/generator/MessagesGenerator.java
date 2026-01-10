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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.Message;

public class MessagesGenerator
{
    private final Map<Message.Type, MessageGenerator> generators = new EnumMap<>(Message.Type.class);

    public MessagesGenerator(ByteBufferPool byteBufferPool)
    {
        ExtensionsGenerator extensionsGenerator = new ExtensionsGenerator();
        generators.put(Message.Type.CLIENT_HELLO, new ClientHelloMessageGenerator(byteBufferPool, extensionsGenerator));
        generators.put(Message.Type.SERVER_HELLO, new ServerHelloMessageGenerator(byteBufferPool, extensionsGenerator));
        generators.put(Message.Type.ENCRYPTED_EXTENSIONS, new EncryptedExtensionsMessageGenerator(byteBufferPool, extensionsGenerator));
        generators.put(Message.Type.CERTIFICATE_REQUEST, new CertificateRequestMessageGenerator(byteBufferPool, extensionsGenerator));
        generators.put(Message.Type.CERTIFICATE, new CertificateMessageGenerator(byteBufferPool, extensionsGenerator));
    }

    public void addListener(MessageGenerator.Listener listener)
    {
        generators.values().forEach(g -> g.addListener(listener));
    }

    public void generate(RetainableByteBuffer.Mutable accumulator, Message message) throws Exception
    {
        MessageGenerator messageGenerator = generators.get(message.type());
        if (messageGenerator == null)
            throw new UnsupportedOperationException("could not generate unsupported TLS message " + message);
        messageGenerator.generate(accumulator, message);
    }
}
