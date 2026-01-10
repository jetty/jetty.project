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

// TODO: this abstraction is necessary because
//  encoding packet numbers requires to know the largest acked
//  packet number, and decoding them requires the largest acked too.
//  So it needs to store the largest acked too, and also
//  managed packet number spaces, that are different from
//  EncryptionLevels.

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.internal.packets.EncodedPacketNumber;

// TODO: I need:
//  - a class that holds epn and epnl, required for generation.
//  - the same class as above is created at parsing, and given along with the largest ack to decode.
//  So basically:
//  * encode(pn, largestAck) : EPN
//  * decode(EPN, largestAck) : long
//  PN are per-encryption-level, so the largestAck can be stored in another class.
public class PacketNumbers
{
    private final Map<Space, Info> infos = new EnumMap<>(Space.class);

    public PacketNumbers()
    {
        infos.put(Space.INITIAL, new Info());
        infos.put(Space.HANDSHAKE, new Info());
        infos.put(Space.APPLICATION, new Info());
    }

    /// Called to record the largest acked number for the given encryption level.
    ///
    /// @param encryptionLevel the encryption level
    /// @param packetNumber the packet number
    public void onAcked(EncryptionLevel encryptionLevel, long packetNumber)
    {
        infos.get(Space.from(encryptionLevel)).onAcked(packetNumber);
    }

    /// @param encryptionLevel the encryption level
    /// @return a new packet number for the given encryption level
    public long nextPacketNumber(EncryptionLevel encryptionLevel)
    {
        return infos.get(Space.from(encryptionLevel)).nextPacketNumber();
    }

    /// @param encryptionLevel the encryption level
    /// @param packetNumber the packet number
    /// @return an [EncodedPacketNumber] from the given encryption level and packet number
    public EncodedPacketNumber encode(EncryptionLevel encryptionLevel, long packetNumber)
    {
        return infos.get(Space.from(encryptionLevel)).encode(packetNumber);
    }

    public long decode(EncryptionLevel encryptionLevel, byte[] encoded)
    {
        int number = 0;
        for (int i = 0; i < encoded.length; ++i)
        {
            number = (number << (i * 8)) | (encoded[i] & 0xFF);
        }
        return decode(encryptionLevel, new EncodedPacketNumber(number, encoded.length));
    }

    public long decode(EncryptionLevel encryptionLevel, EncodedPacketNumber encoded)
    {
        return infos.get(Space.from(encryptionLevel)).decode(encoded);
    }

    private enum Space
    {
        INITIAL,
        HANDSHAKE,
        APPLICATION;

        private static Space from(EncryptionLevel encryptionLevel)
        {
            return switch (encryptionLevel)
            {
                case INITIAL -> INITIAL;
                case HANDSHAKE -> HANDSHAKE;
                case ZERO_RTT, ONE_RTT -> APPLICATION;
            };
        }
    }

    private class Info
    {
        private final AtomicLong ids = new AtomicLong();
        private final AtomicLong acks = new AtomicLong();

        public void onAcked(long packetNumber)
        {
            // TODO
        }

        public long nextPacketNumber()
        {
            // TODO: limit to VarLenInt.MAX_VALUE.
            return ids.getAndIncrement();
        }

        public EncodedPacketNumber encode(long packetNumber)
        {
            // RFC 9000, 17.1 and A.2.
            long acked = acks.get();
            long unacked = acked == 0 ? packetNumber + 1 : packetNumber - acked;
            // Must be able to represent twice the unacked range.
            int bits = 64 - Long.numberOfLeadingZeros(2 * unacked);
            int length = (bits + 7) / 8;
            if (length > 4)
                throw new IllegalStateException("could not encode packet number, bytes required " + length);
            // Convert the number of bytes to a mask of bits set to 1.
            long mask = (1L << (length * 8)) - 1;
            return new EncodedPacketNumber((int)(packetNumber & mask), length);
        }

        public long decode(EncodedPacketNumber encoded)
        {
            // RFC 9000, 17.1 and A.3.
            long acked = acks.get();
            long expected = acked + 1;
            long window = 1L << (encoded.length() * 8);
            long halfWindow = window / 2;
            long mask = window - 1;
            long candidate = (expected & ~mask) | encoded.number();
            if (candidate <= (expected - halfWindow) && candidate < (1L << 62) - window)
                return candidate + window;
            if (candidate > (expected + halfWindow) && candidate >= window)
                return candidate - window;
            return candidate;
        }
    }
}
