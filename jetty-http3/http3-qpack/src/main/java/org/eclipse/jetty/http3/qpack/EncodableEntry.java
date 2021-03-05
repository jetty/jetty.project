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
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.qpack.table.Entry;

abstract class EncodableEntry
{
    static EncodableEntry getReferencedEntry(Entry entry)
    {
        return new ReferencedEntry(entry);
    }

    static EncodableEntry getNameReferencedEntry(Entry nameEntry, HttpField field, boolean huffman)
    {
        return new ReferencedNameEntry(nameEntry, field, huffman);
    }

    static EncodableEntry getLiteralEntry(HttpField field, boolean huffman)
    {
        return new LiteralEntry(field, huffman);
    }

    static EncodableEntry getPreEncodedEntry(PreEncodedHttpField httpField)
    {
        return new PreEncodedEntry(httpField);
    }

    abstract void encode(ByteBuffer buffer, int base);

    abstract int getRequiredSize(int base);

    abstract int getRequiredInsertCount();

    private static class ReferencedEntry extends EncodableEntry
    {
        private final Entry _entry;

        public ReferencedEntry(Entry entry)
        {
            _entry = entry;
        }

        @Override
        public void encode(ByteBuffer buffer, int base)
        {
            byte staticBit = _entry.isStatic() ? (byte)0x40 : (byte)0x00;
            buffer.put((byte)(0x80 | staticBit));
            int relativeIndex =  _entry.getIndex() - base;
            NBitInteger.encode(buffer, 6, relativeIndex);
        }

        @Override
        public int getRequiredSize(int base)
        {
            int relativeIndex =  _entry.getIndex() - base;
            return 1 + NBitInteger.octectsNeeded(6, relativeIndex);
        }

        @Override
        public int getRequiredInsertCount()
        {
            return _entry.isStatic() ? 0 : _entry.getIndex() + 1;
        }
    }

    private static class ReferencedNameEntry extends EncodableEntry
    {
        private final Entry _nameEntry;
        private final HttpField _field;
        private final boolean _huffman;

        public ReferencedNameEntry(Entry nameEntry, HttpField field, boolean huffman)
        {
            _nameEntry = nameEntry;
            _field = field;
            _huffman = huffman;
        }

        @Override
        public void encode(ByteBuffer buffer, int base)
        {
            byte allowIntermediary = 0x00; // TODO: this is 0x20 bit, when should this be set?
            byte staticBit = _nameEntry.isStatic() ? (byte)0x10 : (byte)0x00;

            // Encode the prefix.
            buffer.put((byte)(0x40 | allowIntermediary | staticBit));
            int relativeIndex =  _nameEntry.getIndex() - base;
            NBitInteger.encode(buffer, 4, relativeIndex);

            // Encode the value.
            String value = getValue();
            if (_huffman)
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

        @Override
        public int getRequiredSize(int base)
        {
            String value = getValue();
            int relativeIndex =  _nameEntry.getIndex() - base;
            int valueLength = _huffman ? Huffman.octetsNeeded(value) : value.length();
            return 1 + NBitInteger.octectsNeeded(4, relativeIndex) + 1 + NBitInteger.octectsNeeded(7, valueLength) + valueLength;
        }

        @Override
        public int getRequiredInsertCount()
        {
            return _nameEntry.isStatic() ? 0 : _nameEntry.getIndex() + 1;
        }

        private String getValue()
        {
            String value = Objects.requireNonNull(_field).getValue();
            return (value == null) ? "" : value;
        }
    }

    private static class LiteralEntry extends EncodableEntry
    {
        private final HttpField _field;
        private final boolean _huffman;

        public LiteralEntry(HttpField field, boolean huffman)
        {
            _field = field;
            _huffman = huffman;
        }

        @Override
        public void encode(ByteBuffer buffer, int base)
        {
            byte allowIntermediary = 0x00; // TODO: this is 0x10 bit, when should this be set?
            String name = getName();
            String value = getValue();

            // Encode the prefix code and the name.
            if (_huffman)
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

        @Override
        public int getRequiredSize(int base)
        {
            String name = getName();
            String value = getValue();
            int nameLength = _huffman ? Huffman.octetsNeeded(name) : name.length();
            int valueLength = _huffman ? Huffman.octetsNeeded(value) : value.length();
            return 2 + NBitInteger.octectsNeeded(3, nameLength) + nameLength + NBitInteger.octectsNeeded(7, valueLength) + valueLength;
        }

        @Override
        public int getRequiredInsertCount()
        {
            return 0;
        }

        private String getName()
        {
            String name = Objects.requireNonNull(_field).getName();
            return (name == null) ? "" : name;
        }

        private String getValue()
        {
            String value = Objects.requireNonNull(_field).getValue();
            return (value == null) ? "" : value;
        }
    }

    private static class PreEncodedEntry extends EncodableEntry
    {
        private final PreEncodedHttpField _httpField;

        public PreEncodedEntry(PreEncodedHttpField httpField)
        {
            _httpField = httpField;
        }

        @Override
        public void encode(ByteBuffer buffer, int base)
        {
            _httpField.putTo(buffer, HttpVersion.HTTP_3);
        }

        @Override
        public int getRequiredSize(int base)
        {
            return _httpField.getEncodedLength(HttpVersion.HTTP_3);
        }

        @Override
        public int getRequiredInsertCount()
        {
            return 0;
        }
    }
}
