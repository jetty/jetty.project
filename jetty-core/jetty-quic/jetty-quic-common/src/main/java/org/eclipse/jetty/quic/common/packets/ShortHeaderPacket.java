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

import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.util.TypeUtil;

public final class ShortHeaderPacket extends Packet
{
    private final long packetNumber;
    private final byte[] dstConnectionId;
    private final boolean keyPhase;
    private final boolean spin;
    private final List<Frame> frames;

    public ShortHeaderPacket(long packetNumber, byte[] dstConnectionId, boolean keyPhase, List<Frame> frames)
    {
        this(packetNumber, dstConnectionId, keyPhase, false, frames);
    }

    public ShortHeaderPacket(long packetNumber, byte[] dstConnectionId, boolean keyPhase, boolean spin, List<Frame> frames)
    {
        this.packetNumber = packetNumber;
        this.dstConnectionId = dstConnectionId;
        this.keyPhase = keyPhase;
        this.spin = spin;
        this.frames = frames;
    }

    public long packetNumber()
    {
        return packetNumber;
    }

    @Override
    public byte[] destinationConnectionId()
    {
        return dstConnectionId;
    }

    public boolean keyPhase()
    {
        return keyPhase;
    }

    public boolean spin()
    {
        return spin;
    }

    public List<Frame> frames()
    {
        return frames;
    }

    @Override
    public String toString()
    {
        return "%s@%x[#%d,dcid=%s,kp=%b,s=%b,%s]".formatted(
            TypeUtil.toShortName(getClass()),
            hashCode(),
            packetNumber(),
            destinationConnectionId(),
            keyPhase(),
            spin(),
            frames()
        );
    }
}
