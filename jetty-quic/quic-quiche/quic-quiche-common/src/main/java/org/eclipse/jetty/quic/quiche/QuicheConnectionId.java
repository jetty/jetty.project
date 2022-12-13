//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicheConnectionId
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheConnectionId.class);
    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private final byte[] dcid;
    private final int hashCode;
    private String string;

    private QuicheConnectionId(byte[] dcid)
    {
        this.dcid = Objects.requireNonNull(dcid);
        this.hashCode = Arrays.hashCode(dcid);
    }

    /**
     * Does not consume the packet byte buffer.
     */
    public static QuicheConnectionId fromPacket(ByteBuffer packet)
    {
        byte[] bytes = QuicheConnection.QUICHE_BINDING.fromPacket(packet);
        if (bytes != null && bytes.length == 0)
            throw new IllegalStateException();
        QuicheConnectionId connectionId = bytes == null ? null : new QuicheConnectionId(bytes);
        if (LOG.isDebugEnabled())
            LOG.debug("snooped connection ID from packet: [{}]", connectionId);
        return connectionId;
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
        if (string == null)
            string = bytesToHex(dcid);
        return string;
    }


    private static String bytesToHex(byte[] bytes)
    {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++)
        {
            int c = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_ARRAY[c >>> 4];
            hexChars[i * 2 + 1] = HEX_ARRAY[c & 0x0F];
        }
        return new String(hexChars, StandardCharsets.US_ASCII);
    }
}
