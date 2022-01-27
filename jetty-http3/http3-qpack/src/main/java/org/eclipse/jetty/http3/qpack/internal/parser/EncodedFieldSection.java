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

package org.eclipse.jetty.http3.qpack.internal.parser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.http3.qpack.internal.QpackContext;
import org.eclipse.jetty.http3.qpack.internal.metadata.MetaDataBuilder;
import org.eclipse.jetty.http3.qpack.internal.util.EncodingException;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerParser;
import org.eclipse.jetty.http3.qpack.internal.util.NBitStringParser;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http3.qpack.QpackException.QPACK_DECOMPRESSION_FAILED;

public class EncodedFieldSection
{
    private static final Logger LOG = LoggerFactory.getLogger(EncodedFieldSection.class);

    private final NBitIntegerParser _integerParser = new NBitIntegerParser();
    private final NBitStringParser _stringParser = new NBitStringParser();
    private final List<EncodedField> _encodedFields = new ArrayList<>();

    private final long _streamId;
    private final int _requiredInsertCount;
    private final int _base;
    private final QpackDecoder.Handler _handler;

    public EncodedFieldSection(long streamId, QpackDecoder.Handler handler, int requiredInsertCount, int base, ByteBuffer content) throws QpackException
    {
        _streamId = streamId;
        _requiredInsertCount = requiredInsertCount;
        _base = base;
        _handler = handler;

        try
        {
            while (content.hasRemaining())
            {
                EncodedField encodedField;
                byte firstByte = content.get(content.position());
                if ((firstByte & 0x80) != 0)
                    encodedField = parseIndexedField(content);
                else if ((firstByte & 0x40) != 0)
                    encodedField = parseNameReference(content);
                else if ((firstByte & 0x20) != 0)
                    encodedField = parseLiteralField(content);
                else if ((firstByte & 0x10) != 0)
                    encodedField = parseIndexedFieldPostBase(content);
                else
                    encodedField = parseNameReferencePostBase(content);

                _encodedFields.add(encodedField);
            }
        }
        catch (EncodingException e)
        {
            throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, e.getMessage(), e);
        }
    }

    public long getStreamId()
    {
        return _streamId;
    }

    public QpackDecoder.Handler getHandler()
    {
        return _handler;
    }

    public int getRequiredInsertCount()
    {
        return _requiredInsertCount;
    }

    public MetaData decode(QpackContext context, int maxHeaderSize) throws QpackException
    {
        if (context.getDynamicTable().getInsertCount() < _requiredInsertCount)
            throw new IllegalStateException("Required Insert Count Not Reached");

        MetaDataBuilder metaDataBuilder = new MetaDataBuilder(maxHeaderSize);
        for (EncodedField encodedField : _encodedFields)
        {
            HttpField decodedField = encodedField.decode(context);
            metaDataBuilder.emit(decodedField);
        }
        return metaDataBuilder.build();
    }

    private EncodedField parseIndexedField(ByteBuffer buffer) throws EncodingException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean dynamicTable = (firstByte & 0x40) == 0;
        _integerParser.setPrefix(6);
        int index = _integerParser.decodeInt(buffer);
        if (index < 0)
            throw new EncodingException("invalid_index");
        return new IndexedField(dynamicTable, index);
    }

    private EncodedField parseIndexedFieldPostBase(ByteBuffer buffer) throws EncodingException
    {
        _integerParser.setPrefix(4);
        int index = _integerParser.decodeInt(buffer);
        if (index < 0)
            throw new EncodingException("Invalid Index");

        return new PostBaseIndexedField(index);
    }

    private EncodedField parseNameReference(ByteBuffer buffer) throws EncodingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseLiteralFieldLineWithNameReference: " + BufferUtil.toDetailString(buffer));

        byte firstByte = buffer.get(buffer.position());
        boolean allowEncoding = (firstByte & 0x20) != 0;
        boolean dynamicTable = (firstByte & 0x10) == 0;

        _integerParser.setPrefix(4);
        int nameIndex = _integerParser.decodeInt(buffer);
        if (nameIndex < 0)
            throw new EncodingException("invalid_name_index");

        _stringParser.setPrefix(8);
        String value = _stringParser.decode(buffer);
        if (value == null)
            throw new EncodingException("incomplete_value");

        return new IndexedNameField(allowEncoding, dynamicTable, nameIndex, value);
    }

    private EncodedField parseNameReferencePostBase(ByteBuffer buffer) throws EncodingException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean allowEncoding = (firstByte & 0x08) != 0;

        _integerParser.setPrefix(3);
        int nameIndex = _integerParser.decodeInt(buffer);
        if (nameIndex < 0)
            throw new EncodingException("invalid_index");

        _stringParser.setPrefix(8);
        String value = _stringParser.decode(buffer);
        if (value == null)
            throw new EncodingException("invalid_value");

        return new PostBaseIndexedNameField(allowEncoding, nameIndex, value);
    }

    private EncodedField parseLiteralField(ByteBuffer buffer) throws EncodingException
    {
        byte firstByte = buffer.get(buffer.position());
        boolean allowEncoding = (firstByte & 0x10) != 0;

        _stringParser.setPrefix(4);
        String name = _stringParser.decode(buffer);
        if (name == null)
            throw new EncodingException("invalid_name");

        _stringParser.setPrefix(8);
        String value = _stringParser.decode(buffer);
        if (value == null)
            throw new EncodingException("invalid_value");

        return new LiteralField(allowEncoding, name, value);
    }

    public interface EncodedField
    {
        HttpField decode(QpackContext context);
    }

    private static class LiteralField implements EncodedField
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
        public HttpField decode(QpackContext context)
        {
            if (_dynamicTable)
                return context.getDynamicTable().getAbsolute(_base - (_index + 1)).getHttpField();
            else
                return QpackContext.getStaticTable().get(_index).getHttpField();
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
        public HttpField decode(QpackContext context)
        {
            return context.getDynamicTable().getAbsolute(_base + _index).getHttpField();
        }
    }

    private class IndexedNameField implements EncodedField
    {
        // TODO: what to do with allow encoding?
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
        public HttpField decode(QpackContext context)
        {
            HttpField field;
            if (_dynamicTable)
                field = context.getDynamicTable().getAbsolute(_base - (_nameIndex + 1)).getHttpField();
            else
                field = QpackContext.getStaticTable().get(_nameIndex).getHttpField();

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
        public HttpField decode(QpackContext context)
        {
            HttpField field = context.getDynamicTable().getAbsolute(_base + _nameIndex).getHttpField();
            return new HttpField(field.getHeader(), field.getName(), _value);
        }
    }
}
