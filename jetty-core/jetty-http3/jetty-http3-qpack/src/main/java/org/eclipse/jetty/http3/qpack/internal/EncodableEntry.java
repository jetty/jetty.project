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

package org.eclipse.jetty.http3.qpack.internal;

import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.http.compression.NBitStringEncoder;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;

public abstract class EncodableEntry
{
    public static EncodableEntry getReferencedEntry(Entry entry)
    {
        return new ReferencedEntry(entry);
    }

    public static EncodableEntry getNameReferencedEntry(Entry nameEntry, HttpField field, boolean huffman)
    {
        return new ReferencedNameEntry(nameEntry, field, huffman);
    }

    public static EncodableEntry getLiteralEntry(HttpField field, boolean huffman)
    {
        return new LiteralEntry(field, huffman);
    }

    public static EncodableEntry getPreEncodedEntry(PreEncodedHttpField httpField)
    {
        return new PreEncodedEntry(httpField);
    }

    public abstract void encode(ByteBuffer buffer, int base);

    public abstract int getRequiredSize(int base);

    public abstract int getRequiredInsertCount();

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
            boolean isStatic = _entry.isStatic();
            if (isStatic)
            {
                // Indexed Field Line with Static Reference.
                buffer.put((byte)(0x80 | 0x40));
                int relativeIndex = _entry.getIndex();
                NBitIntegerEncoder.encode(buffer, 6, relativeIndex);
            }
            else if (_entry.getIndex() < base)
            {
                // Indexed Field Line with Dynamic Reference.
                buffer.put((byte)0x80);
                int relativeIndex = base - (_entry.getIndex() + 1);
                NBitIntegerEncoder.encode(buffer, 6, relativeIndex);
            }
            else
            {
                // Indexed Field Line with Post-Base Index.
                buffer.put((byte)0x10);
                int relativeIndex =  _entry.getIndex() - base;
                NBitIntegerEncoder.encode(buffer, 4, relativeIndex);
            }
        }

        @Override
        public int getRequiredSize(int base)
        {
            boolean isStatic = _entry.isStatic();
            if (isStatic)
            {
                // Indexed Field Line with Static Reference.
                int relativeIndex = _entry.getIndex();
                return NBitIntegerEncoder.octetsNeeded(6, relativeIndex);
            }
            else if (_entry.getIndex() < base)
            {
                // Indexed Field Line with Dynamic Reference.
                int relativeIndex =  base - (_entry.getIndex() + 1);
                return NBitIntegerEncoder.octetsNeeded(6, relativeIndex);
            }
            else
            {
                // Indexed Field Line with Post-Base Index.
                int relativeIndex = _entry.getIndex() - base;
                return NBitIntegerEncoder.octetsNeeded(4, relativeIndex);
            }
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
            // TODO: when should this be set?
            boolean allowIntermediary = false;

            // Encode the prefix.
            boolean isStatic = _nameEntry.isStatic();
            if (isStatic)
            {
                // Literal Field Line with Static Name Reference.
                buffer.put((byte)(0x40 | 0x10 | (allowIntermediary ? 0x20 : 0x00)));
                int relativeIndex =  _nameEntry.getIndex();
                NBitIntegerEncoder.encode(buffer, 4, relativeIndex);
            }
            else if (_nameEntry.getIndex() < base)
            {
                // Literal Field Line with Dynamic Name Reference.
                buffer.put((byte)(0x40 | (allowIntermediary ? 0x20 : 0x00)));
                int relativeIndex = base - (_nameEntry.getIndex() + 1);
                NBitIntegerEncoder.encode(buffer, 4, relativeIndex);
            }
            else
            {
                // Literal Field Line with Post-Base Name Reference.
                buffer.put((byte)(allowIntermediary ? 0x08 : 0x00));
                int relativeIndex = _nameEntry.getIndex() - base;
                NBitIntegerEncoder.encode(buffer, 3, relativeIndex);
            }

            // Encode the value.
            NBitStringEncoder.encode(buffer, 8, getValue(), _huffman);
        }

        @Override
        public int getRequiredSize(int base)
        {
            int nameOctets;
            if (_nameEntry.isStatic())
            {
                int relativeIndex =  _nameEntry.getIndex();
                nameOctets = NBitIntegerEncoder.octetsNeeded(4, relativeIndex);
            }
            else if (_nameEntry.getIndex() < base)
            {
                int relativeIndex = base - (_nameEntry.getIndex() + 1);
                nameOctets = NBitIntegerEncoder.octetsNeeded(4, relativeIndex);
            }
            else
            {
                int relativeIndex = _nameEntry.getIndex() - base;
                nameOctets = NBitIntegerEncoder.octetsNeeded(3, relativeIndex);
            }

            return nameOctets + NBitStringEncoder.octetsNeeded(8, getValue(), _huffman);
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

            // Encode the prefix code and the name.
            buffer.put((byte)(0x20 | allowIntermediary));
            NBitStringEncoder.encode(buffer, 4, getName(), _huffman);
            NBitStringEncoder.encode(buffer, 8, getValue(), _huffman);
        }

        @Override
        public int getRequiredSize(int base)
        {
            int encodedNameSize = NBitStringEncoder.octetsNeeded(4, getName(), _huffman);
            int encodedValueSize = NBitStringEncoder.octetsNeeded(8, getValue(), _huffman);
            return encodedNameSize + encodedValueSize;
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
