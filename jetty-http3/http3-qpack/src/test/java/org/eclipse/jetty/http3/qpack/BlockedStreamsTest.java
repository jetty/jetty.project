package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.QpackException.SessionException;
import org.eclipse.jetty.http3.qpack.internal.instruction.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.InsertCountIncrementInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.LiteralNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SetCapacityInstruction;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http3.qpack.QpackTestUtil.encode;
import static org.eclipse.jetty.http3.qpack.QpackTestUtil.toBuffer;
import static org.eclipse.jetty.http3.qpack.QpackTestUtil.toMetaData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlockedStreamsTest
{
    private static final int MAX_BLOCKED_STREAMS = 5;
    private static final int MAX_HEADER_SIZE = 1024;

    private QpackEncoder _encoder;
    private QpackDecoder _decoder;
    private TestDecoderHandler _decoderHandler;
    private TestEncoderHandler _encoderHandler;

    @BeforeEach
    public void before()
    {
        _encoderHandler = new TestEncoderHandler();
        _decoderHandler = new TestDecoderHandler();
        _encoder = new QpackEncoder(_encoderHandler, MAX_BLOCKED_STREAMS);
        _decoder = new QpackDecoder(_decoderHandler, MAX_HEADER_SIZE);
    }

    @Test
    public void testBlockedStreams() throws Exception
    {
        // These settings are determined by HTTP/3 settings frames.
        _encoder.setMaxBlockedStreams(2);
        _decoder.setMaxBlockedStreams(2);

        // Set capacity of the encoder & decoder to allow entries to be added to the table.
        int capacity = 1024;
        _encoder.setCapacity(capacity);
        Instruction instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(SetCapacityInstruction.class));
        _decoder.parseInstructions(QpackTestUtil.toBuffer(instruction));

        // Encode a new field, which will be added to table. But do not forward insertion instruction to decoder,
        // this will cause decoder to become "blocked" on stream 0 until receives the instruction.
        HttpField entry1 = new HttpField("name1", "value1");
        ByteBuffer buffer = encode(_encoder, 0, toMetaData("GET", "/", "http", entry1));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));
        Instruction instruction1 = _encoderHandler.getInstruction();
        assertThat(instruction1, instanceOf(LiteralNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());

        // Decoder will not be able to decode this header until it receives instruction.
        boolean decoded = _decoder.decode(0, buffer, _decoderHandler);
        assertFalse(decoded);
        assertThat(BufferUtil.remaining(buffer), equalTo(0L));
        assertNull(_decoderHandler.getMetaData());
        assertNull(_decoderHandler.getInstruction());

        // Encode second field with dynamic table, do not forward instruction to decoder.
        HttpField entry2 = new HttpField("name1", "value2");
        buffer = encode(_encoder, 1, toMetaData("GET", "/", "http", entry2));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));
        Instruction instruction2 = _encoderHandler.getInstruction();
        assertThat(instruction2, instanceOf(IndexedNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());

        // Decoder will not be able to decode this header until it receives instruction.
        decoded = _decoder.decode(1, buffer, _decoderHandler);
        assertFalse(decoded);
        assertNull(_decoderHandler.getMetaData());
        assertNull(_decoderHandler.getInstruction());

        // Give first instruction to get first metadata.
        _decoder.parseInstructions(QpackTestUtil.toBuffer(instruction1));
        MetaData metaData = _decoderHandler.getMetaData();
        assertThat(metaData.getFields().size(), equalTo(1));
        assertThat(metaData.getFields().get(entry1.getHeader()), equalTo(entry1.getValue()));

        Instruction inc1 = _decoderHandler.getInstruction();
        assertThat(inc1, instanceOf(InsertCountIncrementInstruction.class));
        assertThat(((InsertCountIncrementInstruction)inc1).getIncrement(), equalTo(1));

        Instruction ack1 = _decoderHandler.getInstruction();
        assertThat(ack1, instanceOf(SectionAcknowledgmentInstruction.class));
        assertThat(((SectionAcknowledgmentInstruction)ack1).getStreamId(), equalTo(0L));

        assertNull(_decoderHandler.getMetaData());
        assertNull(_decoderHandler.getInstruction());

        // Give second instruction to get second metadata.
        _decoder.parseInstructions(QpackTestUtil.toBuffer(instruction2));
        metaData = _decoderHandler.getMetaData();
        assertThat(metaData.getFields().size(), equalTo(1));
        assertThat(metaData.getFields().get(entry2.getHeader()), equalTo(entry2.getValue()));

        Instruction inc2 = _decoderHandler.getInstruction();
        assertThat(inc2, instanceOf(InsertCountIncrementInstruction.class));
        assertThat(((InsertCountIncrementInstruction)inc2).getIncrement(), equalTo(1));

        Instruction ack2 = _decoderHandler.getInstruction();
        assertThat(ack2, instanceOf(SectionAcknowledgmentInstruction.class));
        assertThat(((SectionAcknowledgmentInstruction)ack2).getStreamId(), equalTo(1L));

        assertNull(_decoderHandler.getMetaData());
        assertNull(_decoderHandler.getInstruction());

        // The encoder hasn't received any InsertCountIncrementInstruction and so it thinks there are two streams blocked.
        // It should only encode literal entries to not risk blocking another stream on the decoder.
        HttpField entry3 = new HttpField("name3", "value3");
        buffer = encode(_encoder, 3, toMetaData("GET", "/", "http", entry3));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));
        instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(LiteralNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());

        // Can decode literal entry immediately without any further instructions.
        decoded = _decoder.decode(3, buffer, _decoderHandler);
        assertTrue(decoded);
        metaData = _decoderHandler.getMetaData();
        assertThat(metaData.getFields().size(), equalTo(1));
        assertThat(metaData.getFields().get(entry3.getHeader()), equalTo(entry3.getValue()));

        // No longer referencing any streams that have been acknowledged.
        buffer = toBuffer(inc1, ack1, inc2, ack2);
        _encoder.parseInstructions(buffer);
        assertThat(BufferUtil.remaining(buffer), equalTo(0L));
        assertThat(_encoder.getStreamInfoMap().size(), equalTo(0));

        // Encoder can now reference entries not acknowledged by the decoder again.
        HttpField entry4 = new HttpField("name4", "value4");
        buffer = encode(_encoder, 4, toMetaData("GET", "/", "http", entry4));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));
        instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(LiteralNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());
        decoded = _decoder.decode(4, buffer, _decoderHandler);
        assertFalse(decoded);
    }

    @Test
    public void testMaxBlockedStreams() throws Exception
    {
        // Encoder will risk blocking 1 more stream than the decoder will allow.
        _encoder.setMaxBlockedStreams(3);
        _decoder.setMaxBlockedStreams(2);

        // Set capacity of the encoder & decoder to allow entries to be added to the table.
        int capacity = 1024;
        _encoder.setCapacity(capacity);
        Instruction instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(SetCapacityInstruction.class));
        _decoder.parseInstructions(QpackTestUtil.toBuffer(instruction));

        // Encode a new field, which will be added to table. But do not forward insertion instruction to decoder,
        // this will cause decoder to become "blocked" on stream 0 until receives the instruction.
        HttpField entry1 = new HttpField("name1", "value1");
        ByteBuffer buffer = encode(_encoder, 0, toMetaData("GET", "/", "http", entry1));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));
        Instruction instruction1 = _encoderHandler.getInstruction();
        assertThat(instruction1, instanceOf(LiteralNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());

        // Decoder will not be able to decode this header until it receives instruction.
        boolean decoded = _decoder.decode(0, buffer, _decoderHandler);
        assertFalse(decoded);
        assertThat(BufferUtil.remaining(buffer), equalTo(0L));
        assertNull(_decoderHandler.getMetaData());
        assertNull(_decoderHandler.getInstruction());

        // Encode second field with dynamic table, do not forward instruction to decoder.
        HttpField entry2 = new HttpField("name1", "value2");
        buffer = encode(_encoder, 1, toMetaData("GET", "/", "http", entry2));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));
        Instruction instruction2 = _encoderHandler.getInstruction();
        assertThat(instruction2, instanceOf(IndexedNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());

        // Decoder will not be able to decode this header until it receives instruction.
        decoded = _decoder.decode(1, buffer, _decoderHandler);
        assertFalse(decoded);
        assertNull(_decoderHandler.getMetaData());
        assertNull(_decoderHandler.getInstruction());

        // This entry will block a 3rd stream which the decoder must not allow.
        HttpField entry3 = new HttpField("name3", "value3");
        ByteBuffer encodedMetadata = encode(_encoder, 3, toMetaData("GET", "/", "http", entry3));
        assertThat(BufferUtil.remaining(encodedMetadata), greaterThan(0L));
        instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(LiteralNameEntryInstruction.class));
        assertNull(_encoderHandler.getInstruction());

        assertThrows(SessionException.class, () -> _decoder.decode(3, encodedMetadata, _decoderHandler));
    }
}
