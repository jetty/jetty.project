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

package org.eclipse.jetty.quic.common.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.PacketBuffers;

public interface Decrypter
{
    // TODO: remove encryptionLevel parameter? It should be available in the QuicTLS state.
    PacketBuffers decryptLongHeaderPacket(EncryptionLevel encryptionLevel, RetainableByteBuffer encrypted) throws Exception;

    PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, ByteBuffer encrypted) throws Exception;
}
