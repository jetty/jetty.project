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

import java.util.List;

import org.eclipse.jetty.quic.api.QuicVersion;

public final class VersionNegotiationPacket extends LongHeaderPacket
{
    private final List<QuicVersion> supportedVersions;

    public VersionNegotiationPacket(byte[] destinationConnectionId, byte[] sourceConnectionId, List<QuicVersion> supportedVersions)
    {
        super(-1, PacketType.VERSION_NEGOTIATION, null, destinationConnectionId, sourceConnectionId);
        this.supportedVersions = supportedVersions;
    }

    public List<QuicVersion> supportedVersions()
    {
        return supportedVersions;
    }
}
