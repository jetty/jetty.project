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
import org.eclipse.jetty.http.HttpFieldPreEncoder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.qpack.internal.EncodableEntry;
import org.eclipse.jetty.http3.qpack.internal.QpackContext;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.internal.table.StaticTable;

public class QpackFieldPreEncoder implements HttpFieldPreEncoder
{
    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_3;
    }

    @Override
    public byte[] getEncodedField(HttpHeader header, String name, String value)
    {
        StaticTable staticTable = QpackContext.getStaticTable();
        HttpField httpField = new HttpField(header, name, value);

        EncodableEntry encodableEntry;
        boolean notIndexed = QpackEncoder.DO_NOT_INDEX.contains(header);
        boolean huffman = !QpackEncoder.DO_NOT_HUFFMAN.contains(header);
        if (notIndexed)
        {
            encodableEntry = EncodableEntry.getLiteralEntry(httpField, huffman);
        }
        else
        {
            Entry entry = staticTable.get(httpField);
            if (entry != null)
            {
                encodableEntry = EncodableEntry.getReferencedEntry(entry);
            }
            else
            {
                Entry nameEntry = staticTable.get(name);
                if (nameEntry != null)
                {
                    encodableEntry = EncodableEntry.getNameReferencedEntry(nameEntry, httpField, huffman);
                }
                else
                {
                    encodableEntry = EncodableEntry.getLiteralEntry(httpField, huffman);
                }
            }
        }

        // Use a base of zero as we only reference the static table.
        int base = 0;
        byte[] preEncodedBytes = new byte[encodableEntry.getRequiredSize(base)];
        encodableEntry.encode(ByteBuffer.wrap(preEncodedBytes), base);
        return preEncodedBytes;
    }
}
