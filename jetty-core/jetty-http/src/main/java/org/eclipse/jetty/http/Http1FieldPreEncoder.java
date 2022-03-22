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

package org.eclipse.jetty.http;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 *
 */
public abstract class Http1FieldPreEncoder implements HttpFieldPreEncoder
{
    @Override
    public byte[] getEncodedField(HttpHeader header, String headerString, String value)
    {
        if (header != null)
        {
            int cbl = header.getBytesColonSpace().length;
            byte[] bytes = Arrays.copyOf(header.getBytesColonSpace(), cbl + value.length() + 2);
            System.arraycopy(value.getBytes(ISO_8859_1), 0, bytes, cbl, value.length());
            bytes[bytes.length - 2] = (byte)'\r';
            bytes[bytes.length - 1] = (byte)'\n';
            return bytes;
        }

        byte[] n = headerString.getBytes(ISO_8859_1);
        byte[] v = value.getBytes(ISO_8859_1);
        byte[] bytes = Arrays.copyOf(n, n.length + 2 + v.length + 2);
        bytes[n.length] = (byte)':';
        bytes[n.length + 1] = (byte)' ';
        System.arraycopy(v, 0, bytes, n.length + 2, v.length);
        bytes[bytes.length - 2] = (byte)'\r';
        bytes[bytes.length - 1] = (byte)'\n';

        return bytes;
    }
}
