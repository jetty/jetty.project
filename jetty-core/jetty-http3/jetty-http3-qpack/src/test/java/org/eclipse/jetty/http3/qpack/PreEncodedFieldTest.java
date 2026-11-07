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

package org.eclipse.jetty.http3.qpack;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.qpack.internal.EncodableEntry;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

public class PreEncodedFieldTest
{
    @Test
    public void testPreEncodedField()
    {
        HttpField httpField = new HttpField("name", "value");

        EncodableEntry entry = EncodableEntry.getLiteralEntry(httpField, true);
        RetainableByteBuffer.Mutable encodedEntry = RetainableByteBuffer.Mutable.allocate(1024, false);
        entry.encode(encodedEntry, -1);

        PreEncodedHttpField preEncodedField = new PreEncodedHttpField(httpField.getName(), httpField.getValue());
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(1024, false);
        preEncodedField.putTo(buffer, HttpVersion.HTTP_3);

        assertEqual(buffer, encodedEntry);
    }

    public void assertEqual(RetainableByteBuffer b1, RetainableByteBuffer b2)
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
                throw new IllegalStateException("Mismatch at position: " + i);
        }
    }
}
