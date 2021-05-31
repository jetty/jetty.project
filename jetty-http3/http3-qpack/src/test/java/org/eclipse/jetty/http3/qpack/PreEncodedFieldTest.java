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

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.qpack.internal.EncodableEntry;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

public class PreEncodedFieldTest
{
    @Test
    public void testPreEncodedField()
    {
        HttpField httpField = new HttpField("name", "value");

        EncodableEntry entry = EncodableEntry.getLiteralEntry(httpField, true);
        ByteBuffer encodedEntry = BufferUtil.allocate(1024);
        BufferUtil.clearToFill(encodedEntry);
        entry.encode(encodedEntry, -1);
        BufferUtil.flipToFlush(encodedEntry, 0);

        PreEncodedHttpField preEncodedField = new PreEncodedHttpField(httpField.getName(), httpField.getValue());
        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.clearToFill(buffer);
        preEncodedField.putTo(buffer, HttpVersion.HTTP_3);
        BufferUtil.flipToFlush(buffer, 0);

        assertEqual(buffer, encodedEntry);
    }

    public void assertEqual(ByteBuffer b1, ByteBuffer b2)
    {
        if (b1 == null || b2 == null)
        {
            if (b1 != b2)
                throw new IllegalStateException("Buffer is null");
            return;
        }

        if (b1.remaining() != b2.remaining())
            throw new IllegalStateException("Invalid remaining: " + b1.remaining() + "!=" + b2.remaining());

        for (int i = 0; b1.hasRemaining(); i++)
        {
            if (b1.get() != b2.get())
            {
                throw new IllegalStateException("Mismatch at position: " + i);
            }
        }
    }
}
