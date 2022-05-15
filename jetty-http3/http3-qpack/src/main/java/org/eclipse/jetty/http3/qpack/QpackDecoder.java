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

package org.eclipse.jetty.http3.qpack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.internal.QpackContext;
import org.eclipse.jetty.http3.qpack.internal.instruction.InsertCountIncrementInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.StreamCancellationInstruction;
import org.eclipse.jetty.http3.qpack.internal.parser.DecoderInstructionParser;
import org.eclipse.jetty.http3.qpack.internal.parser.EncodedFieldSection;
import org.eclipse.jetty.http3.qpack.internal.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.internal.table.StaticTable;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerParser;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http3.qpack.QpackException.QPACK_DECOMPRESSION_FAILED;
import static org.eclipse.jetty.http3.qpack.QpackException.QPACK_ENCODER_STREAM_ERROR;

public class QpackDecoder implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(QpackDecoder.class);

    private final List<Instruction> _instructions = new ArrayList<>();
    private final List<MetaDataNotification> _metaDataNotifications = new ArrayList<>();
    private final Instruction.Handler _handler;
    private final QpackContext _context;
    private final DecoderInstructionParser _parser;
    private final List<EncodedFieldSection> _encodedFieldSections = new ArrayList<>();
    private final NBitIntegerParser _integerDecoder = new NBitIntegerParser();
    private final InstructionHandler _instructionHandler = new InstructionHandler();
    private final Map<Long, AtomicInteger> _blockedStreams = new HashMap<>();
    private int _maxHeaderSize;
    private int _maxBlockedStreams;

    private static class MetaDataNotification
    {
        private final MetaData _metaData;
        private final Handler _handler;
        private final long _streamId;

        public MetaDataNotification(long streamId, MetaData metaData, Handler handler)
        {
            _streamId = streamId;
            _metaData = metaData;
            _handler = handler;
        }

        public void notifyHandler()
        {
            _handler.onMetaData(_streamId, _metaData);
        }
    }

    /**
     * @param maxHeaderSize The maximum allowed size of a headers block, expressed as total of all name and value characters, plus 32 per field
     */
    public QpackDecoder(Instruction.Handler handler, int maxHeaderSize)
    {
        _context = new QpackContext();
        _handler = handler;
        _parser = new DecoderInstructionParser(_instructionHandler);
        _maxHeaderSize = maxHeaderSize;
    }

    QpackContext getQpackContext()
    {
        return _context;
    }

    public int getMaxHeaderSize()
    {
        return _maxHeaderSize;
    }

    public void setMaxHeaderSize(int maxHeaderSize)
    {
        _maxHeaderSize = maxHeaderSize;
    }

    public int getMaxBlockedStreams()
    {
        return _maxBlockedStreams;
    }

    public void setMaxBlockedStreams(int maxBlockedStreams)
    {
        _maxBlockedStreams = maxBlockedStreams;
    }

    public interface Handler
    {
        void onMetaData(long streamId, MetaData metadata);
    }

    /**
     * <p>Decode a buffer into a {@link MetaData} object given a HTTP/3 stream ID. The buffer must be the complete content of a
     * headers frame and will be fully consumed. It may be that the Dynamic Table does not yet contain the state required
     * to decode this headers frame, in this case the encoded headers will be saved until the required state arrives on the
     * instruction stream to update the dynamic table.</p>
     * <p>This method may generate instructions to be sent back over the Decoder stream to the remote Encoder.</p>
     * @param streamId the stream ID corresponding to this headers frame.
     * @param buffer the content of the headers frame.
     * @param handler a handler that is invoked when the MetaData is able to be decoded.
     * @return true if the MetaData could be decoded immediately without requiring addition state in the DynamicTable.
     * @throws QpackException if there was an error with the QPACK decompression.
     */
    public boolean decode(long streamId, ByteBuffer buffer, Handler handler) throws QpackException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Decoding: streamId={}, buffer={}", streamId, BufferUtil.toDetailString(buffer));

        // If the buffer is big, don't even think about decoding it
        int maxHeaderSize = getMaxHeaderSize();
        if (buffer.remaining() > maxHeaderSize)
            throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "header_too_large");

        _integerDecoder.setPrefix(8);
        int encodedInsertCount = _integerDecoder.decodeInt(buffer);
        if (encodedInsertCount < 0)
            throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "invalid_required_insert_count");

        _integerDecoder.setPrefix(7);
        boolean signBit = (buffer.get(buffer.position()) & 0x80) != 0;
        int deltaBase = _integerDecoder.decodeInt(buffer);
        if (deltaBase < 0)
            throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "invalid_delta_base");

        // Decode the Required Insert Count using the DynamicTable state.
        DynamicTable dynamicTable = _context.getDynamicTable();
        int insertCount = dynamicTable.getInsertCount();
        int maxDynamicTableSize = dynamicTable.getCapacity();
        int requiredInsertCount = decodeInsertCount(encodedInsertCount, insertCount, maxDynamicTableSize);

        try
        {
            // Parse the buffer into an Encoded Field Section.
            int base = signBit ? requiredInsertCount - deltaBase - 1 : requiredInsertCount + deltaBase;
            EncodedFieldSection encodedFieldSection = new EncodedFieldSection(streamId, handler, requiredInsertCount, base, buffer);

            // Decode it straight away if we can, otherwise add it to the list of EncodedFieldSections.
            if (requiredInsertCount <= insertCount)
            {
                MetaData metaData = encodedFieldSection.decode(_context, maxHeaderSize);
                if (LOG.isDebugEnabled())
                    LOG.debug("Decoded: streamId={}, metadata={}", streamId, metaData);
                _metaDataNotifications.add(new MetaDataNotification(streamId, metaData, handler));
                if (requiredInsertCount > 0)
                    _instructions.add(new SectionAcknowledgmentInstruction(streamId));
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Deferred Decoding: streamId={}, encodedFieldSection={}", streamId, encodedFieldSection);
                AtomicInteger blockedFields = _blockedStreams.computeIfAbsent(streamId, id -> new AtomicInteger(0));
                blockedFields.incrementAndGet();
                if (_blockedStreams.size() > _maxBlockedStreams)
                    throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "exceeded max blocked streams");
                _encodedFieldSections.add(encodedFieldSection);
            }

            boolean hadMetaData = !_metaDataNotifications.isEmpty();
            notifyInstructionHandler();
            notifyMetaDataHandler();
            return hadMetaData;
        }
        catch (QpackException.SessionException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new QpackException.SessionException(QPACK_ENCODER_STREAM_ERROR, t.getMessage(), t);
        }
    }

    /**
     * Parse instructions from the Encoder stream. The Encoder stream carries an unframed sequence of instructions from
     * the Encoder to the Decoder. This method will fully consume the supplied {@link ByteBuffer} and produce instructions
     * to update the state of the Decoder and its Dynamic Table.
     * @param buffer a buffer containing bytes from the Encoder stream.
     * @throws QpackException if there was an error parsing or handling the instructions.
     */
    public void parseInstructions(ByteBuffer buffer) throws QpackException
    {
        try
        {
            while (BufferUtil.hasContent(buffer))
            {
                _parser.parse(buffer);
            }
            notifyInstructionHandler();
            notifyMetaDataHandler();
        }
        catch (QpackException.SessionException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new QpackException.SessionException(QPACK_ENCODER_STREAM_ERROR, t.getMessage(), t);
        }
    }

    /**
     * Tells the {@link QpackDecoder} that a particular stream has been cancelled. Any encoded field sections for this stream
     * will be discarded and a stream cancellation instruction will be sent to the remote Encoder.
     * @param streamId the streamId of the stream that was cancelled.
     */
    public void streamCancellation(long streamId)
    {
        _encodedFieldSections.removeIf(encodedFieldSection -> encodedFieldSection.getStreamId() == streamId);
        _blockedStreams.remove(streamId);
        _metaDataNotifications.removeIf(notification -> notification._streamId == streamId);
        _instructions.add(new StreamCancellationInstruction(streamId));
        notifyInstructionHandler();
    }

    private void checkEncodedFieldSections() throws QpackException
    {
        int insertCount = _context.getDynamicTable().getInsertCount();
        Iterator<EncodedFieldSection> iterator = _encodedFieldSections.iterator();
        while (iterator.hasNext())
        {
            EncodedFieldSection encodedFieldSection = iterator.next();
            int requiredInsertCount = encodedFieldSection.getRequiredInsertCount();
            if (requiredInsertCount <= insertCount)
            {
                iterator.remove();
                long streamId = encodedFieldSection.getStreamId();
                MetaData metaData = encodedFieldSection.decode(_context, _maxHeaderSize);
                if (_blockedStreams.get(streamId).decrementAndGet() <= 0)
                    _blockedStreams.remove(streamId);
                if (LOG.isDebugEnabled())
                    LOG.debug("Decoded: streamId={}, metadata={}", streamId, metaData);

                _metaDataNotifications.add(new MetaDataNotification(streamId, metaData, encodedFieldSection.getHandler()));
                if (requiredInsertCount > 0)
                    _instructions.add(new SectionAcknowledgmentInstruction(streamId));
            }
        }
    }

    private static int decodeInsertCount(int encInsertCount, int totalNumInserts, int maxTableCapacity) throws QpackException
    {
        if (encInsertCount == 0)
            return 0;

        int maxEntries = maxTableCapacity / 32;
        int fullRange = 2 * maxEntries;
        if (encInsertCount > fullRange)
            throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "encInsertCount_greater_than_fullRange");

        // MaxWrapped is the largest possible value of ReqInsertCount that is 0 mod 2 * MaxEntries.
        int maxValue = totalNumInserts + maxEntries;
        int maxWrapped = (maxValue / fullRange) * fullRange;
        int reqInsertCount = maxWrapped + encInsertCount - 1;

        // If reqInsertCount exceeds maxValue, the Encoder's value must have wrapped one fewer time.
        if (reqInsertCount > maxValue)
        {
            if (reqInsertCount <= fullRange)
                throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "reqInsertCount_less_than_or_equal_to_fullRange");
            reqInsertCount -= fullRange;
        }

        // Value of 0 must be encoded as 0.
        if (reqInsertCount == 0)
            throw new QpackException.SessionException(QPACK_DECOMPRESSION_FAILED, "reqInsertCount_is_zero");

        return reqInsertCount;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, _context.getDynamicTable());
    }

    @Override
    public String toString()
    {
        return String.format("QpackDecoder@%x{%s}", hashCode(), _context);
    }

    private void notifyInstructionHandler()
    {
        if (!_instructions.isEmpty())
            _handler.onInstructions(_instructions);
        _instructions.clear();
    }

    private void notifyMetaDataHandler()
    {
        for (MetaDataNotification notification : _metaDataNotifications)
        {
            notification.notifyHandler();
        }
        _metaDataNotifications.clear();
    }

    InstructionHandler getInstructionHandler()
    {
        return _instructionHandler;
    }

    /**
     * This delivers notifications from the DecoderInstruction parser directly into the Decoder.
     */
    class InstructionHandler implements DecoderInstructionParser.Handler
    {
        @Override
        public void onSetDynamicTableCapacity(int capacity)
        {
            _context.getDynamicTable().setCapacity(capacity);
        }

        @Override
        public void onDuplicate(int index) throws QpackException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Duplicate: index={}", index);

            DynamicTable dynamicTable = _context.getDynamicTable();
            Entry referencedEntry = dynamicTable.get(index);

            // Add the new Entry to the DynamicTable.
            Entry entry = new Entry(referencedEntry.getHttpField());
            dynamicTable.add(entry);
            _instructions.add(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }

        @Override
        public void onInsertNameWithReference(int nameIndex, boolean isDynamicTableIndex, String value) throws QpackException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InsertNameReference: nameIndex={}, dynamic={}, value={}", nameIndex, isDynamicTableIndex, value);

            StaticTable staticTable = QpackContext.getStaticTable();
            DynamicTable dynamicTable = _context.getDynamicTable();
            Entry referencedEntry = isDynamicTableIndex ? dynamicTable.get(nameIndex) : staticTable.get(nameIndex);

            // Add the new Entry to the DynamicTable.
            Entry entry = new Entry(new HttpField(referencedEntry.getHttpField().getHeader(), referencedEntry.getHttpField().getName(), value));
            dynamicTable.add(entry);
            _instructions.add(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }

        @Override
        public void onInsertWithLiteralName(String name, String value) throws QpackException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InsertLiteralEntry: name={}, value={}", name, value);

            Entry entry = new Entry(new HttpField(name, value));

            // Add the new Entry to the DynamicTable.
            DynamicTable dynamicTable = _context.getDynamicTable();
            dynamicTable.add(entry);
            _instructions.add(new InsertCountIncrementInstruction(1));
            checkEncodedFieldSections();
        }
    }
}
