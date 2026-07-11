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

import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.PaddingFrame;

/// A QUIC packet.
public sealed interface Packet extends AutoCloseable permits DiscardPacket, LongHeaderPacket, Packet.WithFrames, ShortHeaderPacket
{
    Packet DISCARD = new DiscardPacket();

    /// @return whether the packet has the long header form
    static boolean isLongHeader(byte form)
    {
        // RFC 9000, 17.2: long header packets have msb == 1.
        return (form & 0b10000000) == 0b10000000;
    }

    /// @return the packet length in bytes, or -1 if the length is unknown
    long length();

    /// @return the packet destination connection id
    byte[] destinationConnectionId();

    /// Closes this packet, signaling that it won't be used anymore.
    ///
    /// If the packet holds resources, they can be disposed when this method is called.
    @Override
    default void close()
    {
    }

    sealed interface WithFrames extends Packet permits HandshakePacket, InitialPacket, OneRTTPacket, ZeroRTTPacket
    {
        long packetNumber();

        List<Frame> frames();

        /// Returns whether this packet requires acknowledgment.
        ///
        /// A packet requires acknowledgment if it contains
        /// at least one frame that is not:
        ///
        /// * [AckFrame]
        /// * [ConnectionCloseFrame]
        /// * [PaddingFrame]
        ///
        /// @return whether this packet requires acknowledgment
        default boolean requiresAcknowledgement()
        {
            // RFC-9000[1.2,13.2.1]
            for (Frame frame : frames())
            {
                switch (frame)
                {
                    case AckFrame _, ConnectionCloseFrame _, PaddingFrame _ ->
                    {
                    }
                    default ->
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        default void close()
        {
            frames().forEach(Frame::close);
        }
    }
}
