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

package org.eclipse.jetty.quic.common.packets;

import org.eclipse.jetty.quic.api.QuicVersion;

public final class RetryPacket extends LongHeaderPacket
{
    private final byte[] token;
    private final byte[] integrity;

    public RetryPacket(QuicVersion quicVersion, byte[] destinationConnectionId, byte[] sourceConnectionId, byte[] token, byte[] integrity)
    {
        super(PacketType.RETRY, quicVersion, destinationConnectionId, sourceConnectionId);
        this.token = token;
        this.integrity = integrity;
    }

    public byte[] token()
    {
        return token;
    }

    public byte[] integrity()
    {
        return integrity;
    }

    public RetryPacket withIntegrity(byte[] integrity)
    {
        return new RetryPacket(quicVersion(), destinationConnectionId(), sourceConnectionId(), token(), integrity);
    }
}
