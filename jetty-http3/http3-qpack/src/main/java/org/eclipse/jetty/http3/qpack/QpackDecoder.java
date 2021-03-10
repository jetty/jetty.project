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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http3.qpack.internal.QpackContext;
import org.eclipse.jetty.http3.qpack.internal.instruction.InsertCountIncrementInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.http3.qpack.internal.parser.DecoderInstructionParser;
import org.eclipse.jetty.http3.qpack.internal.parser.EncodedFieldSection;
import org.eclipse.jetty.http3.qpack.internal.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.internal.table.StaticTable;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerParser;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qpack Decoder
 * <p>This is not thread safe and may only be called by 1 thread at a time.</p>
 */
public class QpackDecoder implements Dumpable
{
    public static final Logger LOG = LoggerFactory.getLogger(QpackDecoder.class);
    public static final HttpField.LongValueHttpField CONTENT_LENGTH_0 =
        new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, 0L);

    private final Handler _handler;
    private final QpackContext _context;
    private final DecoderInstructionParser _parser;

    private final List<EncodedFieldSection> _encodedFieldSections = new ArrayList<>();
    private final NBitIntegerParser _integerDecoder = new NBitIntegerParser();

    /**
     * @param maxHeaderSize The maximum allowed size of a headers block, expressed as total of all name and value characters, plus 32 per field
     */
    public QpackDecoder(Handler handler, int maxHeaderSize)
    {
        _context = new QpackContext();
        _handler = handler;
        _parser = new DecoderInstructionParser(new DecoderAdapter());
    }

    QpackContext getQpackContext()
    {
        return _context;
    }

    public interface Handler
    {
        void onHttpFields(int streamId, HttpFields httpFields);

        void onInstruction(Instruction instruction) throws QpackException;
    }

    public void decode(int streamId, ByteBuffer buffer) throws QpackException
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("CtxTbl[%x] decoding %d octets", _context.hashCode(), buffer.remaining()));

            // If the buffer is big, don't even think about decoding it
            // if (buffer.remaining() > _builder.getMaxSize())
                //throw new QpackException.SessionException("431 Request Header Fields too large");

            _integerDecoder.setPrefix(8);
            int encodedInsertCount = _integerDecoder.decode(buffer);
            if (encodedInsertCount < 0)
                throw new QpackException.CompressionException("Could not parse Required Insert Count");

            _integerDecoder.setPrefix(7);
            boolean signBit = (buffer.get(buffer.position()) & 0x80) != 0;
            int deltaBase = _integerDecoder.decode(buffer);
            if (deltaBase < 0)
                throw new QpackException.CompressionException("Could not parse Delta Base");

            // Decode the Required Insert Count using the DynamicTable state.
            DynamicTable dynamicTable = _context.getDynamicTable();
            int insertCount = dynamicTable.getInsertCount();
            int maxDynamicTableSize = dynamicTable.getCapacity();
            int requiredInsertCount = decodeInsertCount(encodedInsertCount, insertCount, maxDynamicTableSize);

            // Parse the buffer into an Encoded Field Section.
            int base = signBit ? requiredInsertCount - deltaBase - 1 : requiredInsertCount + deltaBase;
            EncodedFieldSection encodedFieldSection = new EncodedFieldSection(streamId, requiredInsertCount, base);
            encodedFieldSection.parse(buffer);

            if (requiredInsertCount <= insertCount)
            {
                _handler.onHttpFields(streamId, encodedFieldSection.decode(_context));
                _handler.onInstruction(new SectionAcknowledgmentInstruction(streamId));
            }
            else
            {
                _encodedFieldSections.add(encodedFieldSection);
            }
        }
    }

    public void parseInstruction(ByteBuffer buffer) throws QpackException
    {
        _parser.parse(buffer);
    }

    private void checkEncodedFieldSections() throws QpackException
    {
        int insertCount = _context.getDynamicTable().getInsertCount();
        for (EncodedFieldSection encodedFieldSection : _encodedFieldSections)
        {
            if (encodedFieldSection.getRequiredInsertCount() <= insertCount)
            {
                _handler.onHttpFields(encodedFieldSection.getStreamId(), encodedFieldSection.decode(_context));
                _handler.onInstruction(new SectionAcknowledgmentInstruction(encodedFieldSection.getStreamId()));
            }
        }
    }

    void setCapacity(int capacity)
    {
        synchronized (this)
        {
            _context.getDynamicTable().setCapacity(capacity);
        }
    }

    void insert(int index) throws QpackException
    {
        synchronized (this)
        {
            DynamicTable dynamicTable = _context.getDynamicTable();
            Entry entry = dynamicTable.get(index);

            // Add the new Entry to the DynamicTable.
            dynamicTable.add(entry);
            _handler.onInstruction(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
    }

    void insert(int nameIndex, boolean isDynamicTableIndex, String value) throws QpackException
    {
        synchronized (this)
        {
            StaticTable staticTable = QpackContext.getStaticTable();
            DynamicTable dynamicTable = _context.getDynamicTable();
            Entry referencedEntry = isDynamicTableIndex ? dynamicTable.get(nameIndex) : staticTable.get(nameIndex);

            // Add the new Entry to the DynamicTable.
            Entry entry = new Entry(new HttpField(referencedEntry.getHttpField().getHeader(), referencedEntry.getHttpField().getName(), value));
            dynamicTable.add(entry);
            _handler.onInstruction(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
    }

    void insert(String name, String value) throws QpackException
    {
        synchronized (this)
        {
            Entry entry = new Entry(new HttpField(name, value));

            // Add the new Entry to the DynamicTable.
            DynamicTable dynamicTable = _context.getDynamicTable();
            dynamicTable.add(entry);
            _handler.onInstruction(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
    }

    private static int decodeInsertCount(int encInsertCount, int totalNumInserts, int maxTableCapacity) throws QpackException
    {
        if (encInsertCount == 0)
            return 0;

        int maxEntries = maxTableCapacity / 32;
        int fullRange = 2 * maxEntries;
        if (encInsertCount > fullRange)
            throw new QpackException.CompressionException("encInsertCount > fullRange");

        // MaxWrapped is the largest possible value of ReqInsertCount that is 0 mod 2 * MaxEntries.
        int maxValue = totalNumInserts + maxEntries;
        int maxWrapped = (maxValue / fullRange) * fullRange;
        int reqInsertCount = maxWrapped + encInsertCount - 1;

        // If reqInsertCount exceeds maxValue, the Encoder's value must have wrapped one fewer time.
        if (reqInsertCount > maxValue)
        {
            if (reqInsertCount <= fullRange)
                throw new QpackException.CompressionException("reqInsertCount <= fullRange");
            reqInsertCount -= fullRange;
        }

        // Value of 0 must be encoded as 0.
        if (reqInsertCount == 0)
            throw new QpackException.CompressionException("reqInsertCount == 0");

        return reqInsertCount;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        synchronized (this)
        {
            Dumpable.dumpObjects(out, indent, _context.getDynamicTable());
        }
    }

    @Override
    public String toString()
    {
        return String.format("QpackDecoder@%x{%s}", hashCode(), _context);
    }

    /**
     * This delivers notifications from the DecoderInstruction parser directly into the Decoder.
     */
    class DecoderAdapter implements DecoderInstructionParser.Handler
    {
        @Override
        public void onSetDynamicTableCapacity(int capacity)
        {
            setCapacity(capacity);
        }

        @Override
        public void onDuplicate(int index) throws QpackException
        {
            insert(index);
        }

        @Override
        public void onInsertNameWithReference(int nameIndex, boolean isDynamicTableIndex, String value) throws QpackException
        {
            insert(nameIndex, isDynamicTableIndex, value);
        }

        @Override
        public void onInsertWithLiteralName(String name, String value) throws QpackException
        {
            insert(name, value);
        }
    }
}
