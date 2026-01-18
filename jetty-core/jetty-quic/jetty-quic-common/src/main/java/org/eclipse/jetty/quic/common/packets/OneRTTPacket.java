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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

public final class OneRTTPacket extends ShortHeaderPacket implements Packet.WithPacketNumber
{
    private final long packetNumber;
    private final byte[] dstConnectionId;
    private final boolean spin;
    private final boolean keyPhase;
    private final List<Frame> frames;

    public OneRTTPacket(long packetNumber, byte[] dstConnectionId, boolean spin, boolean keyPhase, List<Frame> frames)
    {
        this.packetNumber = packetNumber;
        this.dstConnectionId = dstConnectionId;
        this.spin = spin;
        this.keyPhase = keyPhase;
        this.frames = frames;
    }

    @Override
    public long packetNumber()
    {
        return packetNumber;
    }

    @Override
    public byte[] destinationConnectionId()
    {
        return dstConnectionId;
    }

    public boolean spin()
    {
        return spin;
    }

    public boolean keyPhase()
    {
        return keyPhase;
    }

    public List<Frame> frames()
    {
        return frames;
    }

    @Override
    public String toString()
    {
        return "%s@%x[dcid=%s][#%d][s=%b,kp=%b,%s]".formatted(
            TypeUtil.toShortName(getClass()),
            hashCode(),
            StringUtil.toHexString(destinationConnectionId()),
            packetNumber(),
            spin(),
            keyPhase(),
            frames()
        );
    }
}
