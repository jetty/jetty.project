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
        generators.put(Message.Type.CLIENT_HELLO, new ClientHelloGenerator(byteBufferPool,  extensionsGenerator));
        generators.put(Message.Type.SERVER_HELLO, new ServerHelloGenerator(byteBufferPool,  extensionsGenerator));
        generators.put(Message.Type.ENCRYPTED_EXTENSIONS, new EncryptedExtensionsGenerator(byteBufferPool,  extensionsGenerator));
        generators.put(Message.Type.CERTIFICATE_REQUEST, new CertificateRequestGenerator(byteBufferPool,  extensionsGenerator));
    }

    public void generate(RetainableByteBuffer.Mutable accumulator, Message message)
    {
        MessageGenerator messageGenerator = generators.get(message.getType());
        if (messageGenerator == null)
            throw new UnsupportedOperationException("could not generate unsupported TLS message " + message);
        messageGenerator.generate(accumulator, message);
    }
}
