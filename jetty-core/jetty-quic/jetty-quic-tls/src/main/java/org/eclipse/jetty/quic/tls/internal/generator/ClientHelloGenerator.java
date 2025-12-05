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

package org.eclipse.jetty.quic.tls.internal.generator;

import java.security.SecureRandom;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.ClientHello;

public class ClientHelloGenerator
{
    private final SecureRandom random;
    private final ExtensionsGenerator extensionsGenerator;

    public ClientHelloGenerator(SecureRandom random, ExtensionsGenerator extensionsGenerator)
    {
        this.random = random;
        this.extensionsGenerator = extensionsGenerator;
    }

    public void generate(RetainableByteBuffer.Mutable accumulator, ClientHello clientHello)
    {
        accumulator.putShort((short)0x0303);
        byte[] rnd = new byte[32];
        random.nextBytes(rnd);
        accumulator.put(rnd);
        // Legacy session ID.
        accumulator.put((byte)0x00);
        // Cipher suites.
        // TODO: BouncyCastle has a CipherSuite class, just a collection of static ints.
//        clientHello.getCipherSuites().forEach(e -> generateCipherSuite(accumulator, e));
        // Legacy compression methods.
        accumulator.put((byte)0x00);
        extensionsGenerator.generate(accumulator, clientHello.getExtensions());
    }
}
