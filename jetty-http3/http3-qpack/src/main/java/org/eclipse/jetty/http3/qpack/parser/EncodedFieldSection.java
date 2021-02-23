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

package org.eclipse.jetty.http3.qpack.parser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.MetaDataBuilder;
import org.eclipse.jetty.http3.qpack.QpackContext;
import org.eclipse.jetty.http3.qpack.QpackException;

public class EncodedFieldSection
{
    private final NBitIntegerParser _integerParser = new NBitIntegerParser();
    private final NBitStringParser _stringParser = new NBitStringParser();
    private final List<EncodedField> _encodedFields = new ArrayList<>();

    private final int _streamId;
    private final int _requiredInsertCount;
    private final int _base;

    public EncodedFieldSection(int streamId, int requiredInsertCount, int base)
    {
        _streamId = streamId;
        _requiredInsertCount = requiredInsertCount;
        _base = base;
    }

    public int getStreamId()
    {
        return _streamId;
    }

    public int getRequiredInsertCount()
    {
        return _requiredInsertCount;
    }

    public void parse(ByteBuffer buffer) throws QpackException
    {
        while (buffer.hasRemaining())
        {
            EncodedField encodedField;
            byte firstByte = buffer.get(buffer.position());
            if ((firstByte & 0x80) != 0)
                encodedField = parseIndexedFieldLine(buffer);
            else if ((firstByte & 0x40) != 0)
                encodedField = parseLiteralFieldLineWithNameReference(buffer);
            else if ((firstByte & 0x20) != 0)
                encodedField = parseLiteralFieldLineWithLiteralName(buffer);
            else if ((firstByte & 0x10) != 0)
                encodedField = parseIndexFieldLineWithPostBaseIndex(buffer);
            else
                encodedField = parseLiteralFieldLineWithPostBaseNameReference(buffer);

            _encodedFields.add(encodedField);
        }
    }

    public MetaData decode(QpackContext context, MetaDataBuilder builder) throws QpackException
    {
        if (context.getDynamicTable().getInsertCount() < _requiredInsertCount)
            throw new IllegalStateException("Required Insert Count Not Reached");

        for (EncodedField encodedField : _encodedFields)
        {
            builder.emit(encodedField.decode(context));
        }

        return builder.build();
    }

    private EncodedField parseIndexedFieldLine(ByteBuffer buffer) throws QpackException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean dynamicTable = (firstByte & 0x40) == 0;
        _integerParser.setPrefix(6);
        int index = _integerParser.decode(buffer);
        if (index < 0)
            throw new QpackException.CompressionException("Invalid Index");
        return new IndexedField(dynamicTable, index);
    }

    private EncodedField parseLiteralFieldLineWithNameReference(ByteBuffer buffer) throws QpackException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean allowEncoding = (firstByte & 0x20) != 0;
        boolean dynamicTable = (firstByte & 0x10) == 0;

        _integerParser.setPrefix(4);
        int nameIndex = _integerParser.decode(buffer);
        if (nameIndex < 0)
            throw new QpackException.CompressionException("Invalid Name Index");

        _stringParser.setPrefix(8);
        String value = _stringParser.decode(buffer);
        if (value == null)
            throw new QpackException.CompressionException("Value");

        return new IndexedNameField(allowEncoding, dynamicTable, nameIndex, value);
    }

    private EncodedField parseLiteralFieldLineWithLiteralName(ByteBuffer buffer) throws QpackException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean allowEncoding = (firstByte & 0x10) != 0;

        _stringParser.setPrefix(3);
        String name = _stringParser.decode(buffer);
        if (name == null)
            throw new QpackException.CompressionException("Invalid Name");

        _stringParser.setPrefix(8);
        String value = _stringParser.decode(buffer);
        if (value == null)
            throw new QpackException.CompressionException("Invalid Value");

        return new LiteralField(allowEncoding, name, value);
    }

    private EncodedField parseLiteralFieldLineWithPostBaseNameReference(ByteBuffer buffer) throws QpackException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean allowEncoding = (firstByte & 0x08) != 0;

        _integerParser.setPrefix(3);
        int nameIndex = _integerParser.decode(buffer);
        if (nameIndex < 0)
            throw new QpackException.CompressionException("Invalid Index");

        _stringParser.setPrefix(8);
        String value = _stringParser.decode(buffer);
        if (value == null)
            throw new QpackException.CompressionException("Invalid Value");

        return new PostBaseIndexedNameField(allowEncoding, nameIndex, value);
    }

    private EncodedField parseIndexFieldLineWithPostBaseIndex(ByteBuffer buffer) throws QpackException
    {
        _integerParser.setPrefix(4);
        int index = _integerParser.decode(buffer);
        if (index < 0)
            throw new QpackException.CompressionException("Invalid Index");

        return new PostBaseIndexedField(index);
    }

    public interface EncodedField
    {
        HttpField decode(QpackContext context) throws QpackException;
    }

    private class LiteralField implements EncodedField
    {
        private final boolean _allowEncoding;
        private final String _name;
        private final String _value;

        public LiteralField(boolean allowEncoding, String name, String value)
        {
            _allowEncoding = allowEncoding;
            _name = name;
            _value = value;
        }

        @Override
        public HttpField decode(QpackContext context)
        {
            return new HttpField(_name, _value);
        }
    }

    private class IndexedField implements EncodedField
    {
        private final boolean _dynamicTable;
        private final int _index;

        public IndexedField(boolean dynamicTable, int index)
        {
            _dynamicTable = dynamicTable;
            _index = index;
        }

        @Override
        public HttpField decode(QpackContext context) throws QpackException
        {
            if (_dynamicTable)
                return context.getDynamicTable().getAbsolute(_base + _index + 1).getHttpField();
            else
                return context.getStaticTable().get(_index).getHttpField();
        }
    }

    private class PostBaseIndexedField implements EncodedField
    {
        private final int _index;

        public PostBaseIndexedField(int index)
        {
            _index = index;
        }

        @Override
        public HttpField decode(QpackContext context) throws QpackException
        {
            return context.getDynamicTable().getAbsolute(_base - _index).getHttpField();
        }
    }

    private class IndexedNameField implements EncodedField
    {
        private final boolean _allowEncoding;
        private final boolean _dynamicTable;
        private final int _nameIndex;
        private final String _value;

        public IndexedNameField(boolean allowEncoding, boolean dynamicTable, int nameIndex, String value)
        {
            _allowEncoding = allowEncoding;
            _dynamicTable = dynamicTable;
            _nameIndex = nameIndex;
            _value = value;
        }

        @Override
        public HttpField decode(QpackContext context) throws QpackException
        {
            HttpField field;
            if (_dynamicTable)
                field = context.getDynamicTable().getAbsolute(_base + _nameIndex + 1).getHttpField();
            else
                field = context.getStaticTable().get(_nameIndex).getHttpField();

            return new HttpField(field.getHeader(), field.getName(), _value);
        }
    }

    private class PostBaseIndexedNameField implements EncodedField
    {
        private final boolean _allowEncoding;
        private final int _nameIndex;
        private final String _value;

        public PostBaseIndexedNameField(boolean allowEncoding, int nameIndex, String value)
        {
            _allowEncoding = allowEncoding;
            _nameIndex = nameIndex;
            _value = value;
        }

        @Override
        public HttpField decode(QpackContext context) throws QpackException
        {
            HttpField field = context.getDynamicTable().getAbsolute(_base - _nameIndex).getHttpField();
            return new HttpField(field.getHeader(), field.getName(), _value);
        }
    }
}
