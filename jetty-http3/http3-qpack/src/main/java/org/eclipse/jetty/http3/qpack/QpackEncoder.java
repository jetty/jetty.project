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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.qpack.internal.EncodableEntry;
import org.eclipse.jetty.http3.qpack.internal.QpackContext;
import org.eclipse.jetty.http3.qpack.internal.StreamInfo;
import org.eclipse.jetty.http3.qpack.internal.instruction.DuplicateInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.LiteralNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SetCapacityInstruction;
import org.eclipse.jetty.http3.qpack.internal.parser.EncoderInstructionParser;
import org.eclipse.jetty.http3.qpack.internal.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QpackEncoder implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(QpackEncoder.class);
    public static final HttpField[] STATUSES = new HttpField[599];
    public static final EnumSet<HttpHeader> DO_NOT_HUFFMAN =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.PROXY_AUTHENTICATE,
            HttpHeader.PROXY_AUTHORIZATION);
    public static final EnumSet<HttpHeader> DO_NOT_INDEX =
        EnumSet.of(
            // HttpHeader.C_PATH,  // TODO more data needed
            // HttpHeader.DATE,    // TODO more data needed
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.CONTENT_RANGE,
            HttpHeader.ETAG,
            HttpHeader.IF_MODIFIED_SINCE,
            HttpHeader.IF_UNMODIFIED_SINCE,
            HttpHeader.IF_NONE_MATCH,
            HttpHeader.IF_RANGE,
            HttpHeader.IF_MATCH,
            HttpHeader.LOCATION,
            HttpHeader.RANGE,
            HttpHeader.RETRY_AFTER,
            // HttpHeader.EXPIRES,
            HttpHeader.LAST_MODIFIED,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);
    public static final EnumSet<HttpHeader> NEVER_INDEX =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);
    private static final EnumSet<HttpHeader> IGNORED_HEADERS = EnumSet.of(HttpHeader.CONNECTION, HttpHeader.KEEP_ALIVE,
        HttpHeader.PROXY_CONNECTION, HttpHeader.TRANSFER_ENCODING, HttpHeader.UPGRADE);
    public static final PreEncodedHttpField TE_TRAILERS = new PreEncodedHttpField(HttpHeader.TE, "trailers");
    public static final PreEncodedHttpField C_SCHEME_HTTP = new PreEncodedHttpField(HttpHeader.C_SCHEME, "http");
    public static final PreEncodedHttpField C_SCHEME_HTTPS = new PreEncodedHttpField(HttpHeader.C_SCHEME, "https");
    public static final EnumMap<HttpMethod, PreEncodedHttpField> C_METHODS = new EnumMap<>(HttpMethod.class);

    static
    {
        for (HttpStatus.Code code : HttpStatus.Code.values())
        {
            STATUSES[code.getCode()] = new PreEncodedHttpField(HttpHeader.C_STATUS, Integer.toString(code.getCode()));
        }
        for (HttpMethod method : HttpMethod.values())
        {
            C_METHODS.put(method, new PreEncodedHttpField(HttpHeader.C_METHOD, method.asString()));
        }
    }

    public interface Handler
    {
        void onInstruction(Instruction instruction) throws QpackException;
    }

    private final ByteBufferPool _bufferPool;
    private final Handler _handler;
    private final QpackContext _context;
    private final int _maxBlockedStreams;
    private final Map<Integer, StreamInfo> _streamInfoMap = new HashMap<>();
    private final EncoderInstructionParser _parser;
    private int _knownInsertCount;
    private int _blockedStreams = 0;

    public QpackEncoder(Handler handler, int maxBlockedStreams)
    {
        this(handler, maxBlockedStreams, new NullByteBufferPool());
    }

    public QpackEncoder(Handler handler, int maxBlockedStreams, ByteBufferPool bufferPool)
    {
        _handler = handler;
        _bufferPool = bufferPool;
        _context = new QpackContext();
        _maxBlockedStreams = maxBlockedStreams;
        _knownInsertCount = 0;
        _parser = new EncoderInstructionParser(new EncoderAdapter());
    }

    QpackContext getQpackContext()
    {
        return _context;
    }

    /**
     * Set the capacity of the DynamicTable and send a instruction to set the capacity on the remote Decoder.
     * @param capacity the new capacity.
     * @throws QpackException
     */
    public void setCapacity(int capacity) throws QpackException
    {
        synchronized (this)
        {
            _context.getDynamicTable().setCapacity(capacity);
            _handler.onInstruction(new SetCapacityInstruction(capacity));
        }
    }

    public void parseInstruction(ByteBuffer buffer) throws QpackException
    {
        _parser.parse(buffer);
    }

    void insertCountIncrement(int increment) throws QpackException
    {
        synchronized (this)
        {
            int insertCount = _context.getDynamicTable().getInsertCount();
            if (_knownInsertCount + increment > insertCount)
                throw new QpackException.StreamException("KnownInsertCount incremented over InsertCount");
            _knownInsertCount += increment;
        }
    }

    void sectionAcknowledgement(int streamId) throws QpackException
    {
        synchronized (this)
        {
            StreamInfo streamInfo = _streamInfoMap.get(streamId);
            if (streamInfo == null)
                throw new QpackException.StreamException("No StreamInfo for " + streamId);

            // The KnownInsertCount should be updated to the earliest sent RequiredInsertCount on that stream.
            StreamInfo.SectionInfo sectionInfo = streamInfo.acknowledge();
            sectionInfo.release();
            _knownInsertCount = Math.max(_knownInsertCount, sectionInfo.getRequiredInsertCount());

            // If we have no more outstanding section acknowledgments remove the StreamInfo.
            if (streamInfo.isEmpty())
                _streamInfoMap.remove(streamId);
        }
    }

    void streamCancellation(int streamId) throws QpackException
    {
        synchronized (this)
        {
            StreamInfo streamInfo = _streamInfoMap.remove(streamId);
            if (streamInfo == null)
                throw new QpackException.StreamException("No StreamInfo for " + streamId);

            // Release all referenced entries outstanding on the stream that was cancelled.
            for (StreamInfo.SectionInfo sectionInfo : streamInfo)
            {
                sectionInfo.release();
            }
        }
    }

    private boolean referenceEntry(Entry entry, StreamInfo streamInfo)
    {
        if (entry == null)
            return false;

        if (entry.isStatic())
            return true;

        boolean inEvictionZone = !_context.getDynamicTable().canReference(entry);
        if (inEvictionZone)
            return false;

        StreamInfo.SectionInfo sectionInfo = streamInfo.getCurrentSectionInfo();

        // If they have already acknowledged this entry we can reference it straight away.
        if (_knownInsertCount >= entry.getIndex() + 1)
        {
            sectionInfo.reference(entry);
            return true;
        }

        // We may need to risk blocking the stream in order to reference it.
        if (streamInfo.isBlocked())
        {
            sectionInfo.block();
            sectionInfo.reference(entry);
            return true;
        }

        if (_blockedStreams < _maxBlockedStreams)
        {
            _blockedStreams++;
            sectionInfo.block();
            sectionInfo.reference(entry);
            return true;
        }

        return false;
    }

    protected boolean shouldIndex(HttpField httpField)
    {
        return !DO_NOT_INDEX.contains(httpField.getHeader());
    }

    protected boolean shouldHuffmanEncode(HttpField httpField)
    {
        return !DO_NOT_HUFFMAN.contains(httpField.getHeader());
    }

    // TODO: Pass in buffer.
    public ByteBuffer encode(int streamId, MetaData metadata) throws QpackException
    {
        // Verify that we can encode without errors.
        if (metadata.getFields() != null)
        {
            for (HttpField field :  metadata.getFields())
            {
                String name = field.getName();
                char firstChar = name.charAt(0);
                if (firstChar <= ' ')
                    throw new QpackException.StreamException("Invalid header name: '%s'", name);
            }
        }

        int base;
        int encodedInsertCount;
        boolean signBit;
        int deltaBase;
        List<EncodableEntry> encodableEntries = new ArrayList<>();
        synchronized (this)
        {
            DynamicTable dynamicTable = _context.getDynamicTable();

            StreamInfo streamInfo = _streamInfoMap.get(streamId);
            if (streamInfo == null)
            {
                streamInfo = new StreamInfo(streamId);
                _streamInfoMap.put(streamId, streamInfo);
            }
            StreamInfo.SectionInfo sectionInfo = new StreamInfo.SectionInfo();
            streamInfo.add(sectionInfo);

            int requiredInsertCount = 0;

            // This will also extract pseudo headers from the metadata.
            Http3Fields httpFields = new Http3Fields(metadata);
            for (HttpField field : httpFields)
            {
                EncodableEntry entry = encode(streamInfo, field);
                encodableEntries.add(entry);

                // Update the required InsertCount.
                int entryRequiredInsertCount = entry.getRequiredInsertCount();
                if (entryRequiredInsertCount > requiredInsertCount)
                    requiredInsertCount = entryRequiredInsertCount;
            }

            sectionInfo.setRequiredInsertCount(requiredInsertCount);
            base = dynamicTable.getBase();
            encodedInsertCount = encodeInsertCount(requiredInsertCount, dynamicTable.getCapacity());
            signBit = base < requiredInsertCount;
            deltaBase = signBit ? requiredInsertCount - base - 1 : base - requiredInsertCount;
        }

        // Calculate the size required. TODO: it may be more efficient to just use a buffer of MAX_HEADER_SIZE?
        int spaceRequired = 0;
        spaceRequired += 1 + NBitIntegerEncoder.octectsNeeded(8, encodedInsertCount);
        spaceRequired += 1 + NBitIntegerEncoder.octectsNeeded(7, deltaBase);
        for (EncodableEntry encodableEntry : encodableEntries)
        {
            spaceRequired += encodableEntry.getRequiredSize(base);
        }

        ByteBuffer buffer = _bufferPool.acquire(spaceRequired, false);
        int pos = BufferUtil.flipToFill(buffer);

        // Encode the Field Section Prefix into the ByteBuffer.
        NBitIntegerEncoder.encode(buffer, 8, encodedInsertCount);
        buffer.put(signBit ? (byte)0x80 : (byte)0x00);
        NBitIntegerEncoder.encode(buffer, 7, deltaBase);

        // Encode the field lines into the ByteBuffer.
        for (EncodableEntry entry : encodableEntries)
        {
            entry.encode(buffer, base);
        }

        BufferUtil.flipToFlush(buffer, pos);
        return buffer;
    }

    private EncodableEntry encode(StreamInfo streamInfo, HttpField field) throws QpackException
    {
        DynamicTable dynamicTable = _context.getDynamicTable();

        if (field.getValue() == null)
            field = new HttpField(field.getHeader(), field.getName(), "");

        // TODO: The field.getHeader() could be null.

        if (field instanceof PreEncodedHttpField)
            return EncodableEntry.getPreEncodedEntry((PreEncodedHttpField)field);

        boolean canCreateEntry = shouldIndex(field) && dynamicTable.canInsert(field);

        Entry entry = _context.get(field);
        if (referenceEntry(entry, streamInfo))
        {
            return EncodableEntry.getReferencedEntry(entry);
        }
        else
        {
            // Should we duplicate this entry.
            if (entry != null && canCreateEntry)
            {
                int index = _context.indexOf(entry);
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _handler.onInstruction(new DuplicateInstruction(index));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return EncodableEntry.getReferencedEntry(newEntry);
            }
        }

        boolean huffman = shouldHuffmanEncode(field);
        Entry nameEntry = _context.get(field.getName());
        if (referenceEntry(nameEntry, streamInfo))
        {
            // Should we copy this entry
            if (canCreateEntry)
            {
                int index = _context.indexOf(nameEntry);
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _handler.onInstruction(new IndexedNameEntryInstruction(!nameEntry.isStatic(), index, huffman, field.getValue()));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return EncodableEntry.getReferencedEntry(newEntry);
            }

            return EncodableEntry.getNameReferencedEntry(nameEntry, field, huffman);
        }
        else
        {
            if (canCreateEntry)
            {
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _handler.onInstruction(new LiteralNameEntryInstruction(huffman, field.getName(), huffman, field.getValue()));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return EncodableEntry.getReferencedEntry(newEntry);
            }

            return EncodableEntry.getLiteralEntry(field, huffman);
        }
    }

    public boolean insert(HttpField field) throws QpackException
    {
        synchronized (this)
        {
            DynamicTable dynamicTable = _context.getDynamicTable();

            if (field.getValue() == null)
                field = new HttpField(field.getHeader(), field.getName(), "");

            boolean canCreateEntry = shouldIndex(field) &&  dynamicTable.canInsert(field);
            if (!canCreateEntry)
                return false;

            // We can always reference on insertion as it will always arrive before any eviction.
            Entry entry = _context.get(field);
            if (entry != null)
            {
                int index = _context.indexOf(entry);
                dynamicTable.add(new Entry(field));
                _handler.onInstruction(new DuplicateInstruction(index));
                return true;
            }

            boolean huffman = shouldHuffmanEncode(field);
            Entry nameEntry = _context.get(field.getName());
            if (nameEntry != null)
            {
                int index = _context.indexOf(nameEntry);
                dynamicTable.add(new Entry(field));
                _handler.onInstruction(new IndexedNameEntryInstruction(!nameEntry.isStatic(), index, huffman, field.getValue()));
                return true;
            }

            dynamicTable.add(new Entry(field));
            _handler.onInstruction(new LiteralNameEntryInstruction(huffman, field.getName(), huffman, field.getValue()));
            return true;
        }
    }

    private static int encodeInsertCount(int reqInsertCount, int maxTableCapacity)
    {
        if (reqInsertCount == 0)
            return 0;

        int maxEntries = maxTableCapacity / 32;
        return (reqInsertCount % (2 * maxEntries)) + 1;
    }

    public class EncoderAdapter implements EncoderInstructionParser.Handler
    {
        @Override
        public void onSectionAcknowledgement(int streamId) throws QpackException
        {
            sectionAcknowledgement(streamId);
        }

        @Override
        public void onStreamCancellation(int streamId) throws QpackException
        {
            streamCancellation(streamId);
        }

        @Override
        public void onInsertCountIncrement(int increment) throws QpackException
        {
            insertCountIncrement(increment);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        synchronized (this)
        {
            Dumpable.dumpObjects(out, indent, _context.getDynamicTable());
        }
    }
}
