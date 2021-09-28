//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.quiche;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import org.eclipse.jetty.quic.quiche.ffi.LibQuiche;
import org.eclipse.jetty.quic.quiche.ffi.size_t;
import org.eclipse.jetty.quic.quiche.ffi.size_t_pointer;
import org.eclipse.jetty.quic.quiche.ffi.uint32_t_pointer;
import org.eclipse.jetty.quic.quiche.ffi.uint8_t_pointer;

public class QuicheConnectionId
{
    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    static
    {
        LibQuiche.Logging.enable();
    }

    private final byte[] dcid;
    private final int hashCode;
    private final String string;

    private QuicheConnectionId(byte[] dcid)
    {
        this.dcid = Objects.requireNonNull(dcid);
        this.hashCode = Arrays.hashCode(dcid);
        this.string = bytesToHex(dcid);
    }

    private static String bytesToHex(byte[] bytes)
    {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++)
        {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.US_ASCII);
    }

    public static QuicheConnectionId fromCid(byte[] cid)
    {
        byte[] sizedDcid = resizeIfNeeded(cid, cid.length);
        return new QuicheConnectionId(sizedDcid);
    }

    static QuicheConnectionId fromCid(byte[] dcid, size_t_pointer dcidLen)
    {
        byte[] sizedDcid = resizeIfNeeded(dcid, (int)dcidLen.getValue());
        return new QuicheConnectionId(sizedDcid);
    }

    /**
     * Does not consume the packet byte buffer.
     */
    public static QuicheConnectionId fromPacket(ByteBuffer packet)
    {
        uint8_t_pointer type = new uint8_t_pointer();
        uint32_t_pointer version = new uint32_t_pointer();

        // Source Connection ID
        byte[] scid = new byte[LibQuiche.QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer scidLen = new size_t_pointer(scid.length);

        // Destination Connection ID
        byte[] dcid = new byte[LibQuiche.QUICHE_MAX_CONN_ID_LEN];
        size_t_pointer dcidLen = new size_t_pointer(dcid.length);

        byte[] token = new byte[48]; // TODO the token buffer size depends on the token minter.
        size_t_pointer tokenLen = new size_t_pointer(token.length);

        int rc = LibQuiche.INSTANCE.quiche_header_info(packet, new size_t(packet.remaining()), new size_t(LibQuiche.QUICHE_MAX_CONN_ID_LEN),
            version, type,
            scid, scidLen,
            dcid, dcidLen,
            token, tokenLen);
        if (rc < 0)
            return null;
        return fromCid(dcid, dcidLen);
    }

    private static byte[] resizeIfNeeded(byte[] buffer, int length)
    {
        byte[] sizedBuffer;
        if (length == buffer.length)
        {
            sizedBuffer = buffer;
        }
        else
        {
            sizedBuffer = new byte[length];
            System.arraycopy(buffer, 0, sizedBuffer, 0, sizedBuffer.length);
        }
        return sizedBuffer;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        QuicheConnectionId that = (QuicheConnectionId)o;
        return Arrays.equals(dcid, that.dcid);
    }

    @Override
    public int hashCode()
    {
        return hashCode;
    }

    @Override
    public String toString()
    {
        return string;
    }
}
