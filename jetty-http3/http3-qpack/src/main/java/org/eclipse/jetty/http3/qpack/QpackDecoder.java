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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.generator.DuplicateInstruction;
import org.eclipse.jetty.http3.qpack.generator.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.generator.InsertCountIncrementInstruction;
import org.eclipse.jetty.http3.qpack.generator.Instruction;
import org.eclipse.jetty.http3.qpack.generator.LiteralNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.generator.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.http3.qpack.generator.SetCapacityInstruction;
import org.eclipse.jetty.http3.qpack.parser.EncodedFieldSection;
import org.eclipse.jetty.http3.qpack.parser.NBitIntegerParser;
import org.eclipse.jetty.http3.qpack.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.table.Entry;
import org.eclipse.jetty.http3.qpack.table.StaticTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qpack Decoder
 * <p>This is not thread safe and may only be called by 1 thread at a time.</p>
 */
public class QpackDecoder
{
    public static final Logger LOG = LoggerFactory.getLogger(QpackDecoder.class);
    public static final HttpField.LongValueHttpField CONTENT_LENGTH_0 =
        new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, 0L);

    private final Handler _handler;
    private final QpackContext _context;
    private final MetaDataBuilder _builder;

    private final List<EncodedFieldSection> _encodedFieldSections = new ArrayList<>();
    private final NBitIntegerParser _integerDecoder = new NBitIntegerParser();

    /**
     * @param localMaxDynamicTableSize The maximum allowed size of the local dynamic header field table.
     * @param maxHeaderSize The maximum allowed size of a headers block, expressed as total of all name and value characters, plus 32 per field
     */
    public QpackDecoder(Handler handler, int localMaxDynamicTableSize, int maxHeaderSize)
    {
        _context = new QpackContext(localMaxDynamicTableSize);
        _builder = new MetaDataBuilder(maxHeaderSize);
        _handler = handler;
    }

    public QpackContext getQpackContext()
    {
        return _context;
    }

    public interface Handler
    {
        void onMetadata(MetaData metaData);

        void onInstruction(Instruction instruction);
    }

    public void decode(int streamId, ByteBuffer buffer) throws QpackException
    {
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] decoding %d octets", _context.hashCode(), buffer.remaining()));

        // If the buffer is big, don't even think about decoding it
        if (buffer.remaining() > _builder.getMaxSize())
            throw new QpackException.SessionException("431 Request Header Fields too large");

        int encodedInsertCount = _integerDecoder.decode(buffer);
        if (encodedInsertCount < 0)
            throw new QpackException.CompressionException("Could not parse Required Insert Count");

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

        if (encodedFieldSection.getRequiredInsertCount() >= insertCount)
        {
            MetaData metadata = encodedFieldSection.decode(_context, _builder);
            _handler.onMetadata(metadata);
            _handler.onInstruction(new SectionAcknowledgmentInstruction(streamId));
        }
        else
        {
            _encodedFieldSections.add(encodedFieldSection);
        }
    }

    private void checkEncodedFieldSections() throws QpackException
    {
        int insertCount = _context.getDynamicTable().getInsertCount();
        for (EncodedFieldSection encodedFieldSection : _encodedFieldSections)
        {
            if (encodedFieldSection.getRequiredInsertCount() <= insertCount)
            {
                MetaData metadata = encodedFieldSection.decode(_context, _builder);
                _handler.onMetadata(metadata);
                _handler.onInstruction(new SectionAcknowledgmentInstruction(encodedFieldSection.getStreamId()));
            }
        }
    }

    public void onInstruction(Instruction instruction) throws QpackException
    {
        StaticTable staticTable = _context.getStaticTable();
        DynamicTable dynamicTable = _context.getDynamicTable();
        if (instruction instanceof SetCapacityInstruction)
        {
            int capacity = ((SetCapacityInstruction)instruction).getCapacity();
            dynamicTable.setCapacity(capacity);
        }
        else if (instruction instanceof DuplicateInstruction)
        {
            DuplicateInstruction duplicate = (DuplicateInstruction)instruction;
            Entry entry = dynamicTable.get(duplicate.getIndex());

            // Add the new Entry to the DynamicTable.
            dynamicTable.add(entry);
            _handler.onInstruction(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
        else if (instruction instanceof IndexedNameEntryInstruction)
        {
            IndexedNameEntryInstruction nameEntryInstruction = (IndexedNameEntryInstruction)instruction;
            int index = nameEntryInstruction.getIndex();
            String value = nameEntryInstruction.getValue();
            Entry referencedEntry = nameEntryInstruction.isDynamic() ? dynamicTable.get(index) : staticTable.get(index);

            // Add the new Entry to the DynamicTable.
            Entry entry = new Entry(new HttpField(referencedEntry.getHttpField().getHeader(), referencedEntry.getHttpField().getName(), value));
            dynamicTable.add(entry);
            _handler.onInstruction(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
        else if (instruction instanceof LiteralNameEntryInstruction)
        {
            LiteralNameEntryInstruction literalEntryInstruction = (LiteralNameEntryInstruction)instruction;
            String name = literalEntryInstruction.getName();
            String value = literalEntryInstruction.getValue();
            Entry entry = new Entry(new HttpField(name, value));

            // Add the new Entry to the DynamicTable.
            dynamicTable.add(entry);
            _handler.onInstruction(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
        else
        {
            throw new IllegalStateException("Invalid Encoder Instruction");
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
    public String toString()
    {
        return String.format("QpackDecoder@%x{%s}", hashCode(), _context);
    }
}
