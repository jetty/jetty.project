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
import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http3.qpack.table.Entry;

class EncodableEntry
{
    private final Entry _referencedEntry;
    private final Entry _referencedName;
    private final HttpField _field;

    public EncodableEntry(Entry entry)
    {
        // We want to reference the entry directly.
        _referencedEntry = entry;
        _referencedName = null;
        _field = null;
    }

    public EncodableEntry(Entry nameEntry, HttpField field)
    {
        // We want to reference the name and use a literal value.
        _referencedEntry = null;
        _referencedName = nameEntry;
        _field = field;
    }

    public EncodableEntry(HttpField field)
    {
        // We want to use a literal name and value.
        _referencedEntry = null;
        _referencedName = null;
        _field = field;
    }

    public void encode(ByteBuffer buffer, int base)
    {
        // TODO: When should we ever encode with post-base indexes?
        //  We are currently always using the base as the start of the current dynamic table entries.
        if (_referencedEntry != null)
        {
            byte staticBit = _referencedEntry.isStatic() ? (byte)0x40 : (byte)0x00;
            buffer.put((byte)(0x80 | staticBit));
            int relativeIndex =  _referencedEntry.getIndex() - base;
            NBitInteger.encode(buffer, 6, relativeIndex);
        }
        else if (_referencedName != null)
        {
            byte allowIntermediary = 0x00; // TODO: this is 0x20 bit, when should this be set?
            byte staticBit = _referencedName.isStatic() ? (byte)0x10 : (byte)0x00;
            String value = (Objects.requireNonNull(_field).getValue() == null) ? "" : _field.getValue();
            boolean huffman = QpackEncoder.shouldHuffmanEncode(_referencedName.getHttpField());

            // Encode the prefix.
            buffer.put((byte)(0x40 | allowIntermediary | staticBit));
            int relativeIndex =  _referencedName.getIndex() - base;
            NBitInteger.encode(buffer, 4, relativeIndex);

            // Encode the value.
            if (huffman)
            {
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer, 7, Huffman.octetsNeeded(value));
                Huffman.encode(buffer, value);
            }
            else
            {
                buffer.put((byte)0x00);
                NBitInteger.encode(buffer, 7, value.length());
                buffer.put(value.getBytes());
            }
        }
        else
        {
            byte allowIntermediary = 0x00; // TODO: this is 0x10 bit, when should this be set?
            boolean huffman = QpackEncoder.shouldHuffmanEncode(Objects.requireNonNull(_field));
            String name = _field.getName();
            String value = (_field.getValue() == null) ? "" : _field.getValue();

            // Encode the prefix code and the name.
            if (huffman)
            {
                buffer.put((byte)(0x28 | allowIntermediary));
                NBitInteger.encode(buffer, 3, Huffman.octetsNeeded(name));
                Huffman.encode(buffer, name);
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer, 7, Huffman.octetsNeeded(value));
                Huffman.encode(buffer, value);
            }
            else
            {
                // TODO: What charset should we be using? (this applies to the instruction generators as well).
                buffer.put((byte)(0x20 | allowIntermediary));
                NBitInteger.encode(buffer, 3, name.length());
                buffer.put(name.getBytes());
                buffer.put((byte)0x00);
                NBitInteger.encode(buffer, 7, value.length());
                buffer.put(value.getBytes());
            }
        }
    }

    public int getRequiredInsertCount()
    {
        if (_referencedEntry != null && !_referencedEntry.isStatic())
            return _referencedEntry.getIndex() + 1;
        else if (_referencedName != null && !_referencedName.isStatic())
            return _referencedName.getIndex() + 1;
        else
            return 0;
    }
}
