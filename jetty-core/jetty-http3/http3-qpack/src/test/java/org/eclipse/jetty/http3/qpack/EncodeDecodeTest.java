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
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.internal.instruction.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.InsertCountIncrementInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.LiteralNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SetCapacityInstruction;
import org.eclipse.jetty.http3.qpack.internal.parser.DecoderInstructionParser;
import org.eclipse.jetty.http3.qpack.internal.parser.EncoderInstructionParser;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncodeDecodeTest
{
    private QpackEncoder _encoder;
    private QpackDecoder _decoder;
    private TestDecoderHandler _decoderHandler;
    private TestEncoderHandler _encoderHandler;

    private EncoderInstructionParser _encoderInstructionParser;
    private DecoderInstructionParser _decoderInstructionParser;

    private static final int MAX_BLOCKED_STREAMS = 5;
    private static final int MAX_HEADER_SIZE = 1024;

    @BeforeEach
    public void before()
    {
        _encoderHandler = new TestEncoderHandler();
        _decoderHandler = new TestDecoderHandler();
        _encoder = new QpackEncoder(_encoderHandler, MAX_BLOCKED_STREAMS)
        {
            @Override
            protected boolean shouldHuffmanEncode(HttpField httpField)
            {
                return false;
            }
        };
        _decoder = new QpackDecoder(_decoderHandler, MAX_HEADER_SIZE);

        _encoderInstructionParser = new EncoderInstructionParser(new EncoderParserDebugHandler(_encoder));
        _decoderInstructionParser = new DecoderInstructionParser(new DecoderParserDebugHandler(_decoder));
    }

    @Disabled("We can't test examples from the spec as we use MetaData")
    @Test
    public void test() throws Exception
    {
        // B.1. Literal Field Line With Name Reference.
        long streamId = 0;
        HttpFields httpFields = HttpFields.build().add(":path", "/index.html");

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        _encoder.encode(buffer, streamId, new MetaData(HttpVersion.HTTP_3, httpFields));
        buffer.flip();
        assertNull(_encoderHandler.getInstruction());
        assertThat(BufferUtil.toHexString(buffer), QpackTestUtil.equalsHex("0000 510b 2f69 6e64 6578 2e68 746d 6c"));
        assertTrue(_encoderHandler.isEmpty());

        _decoder.decode(streamId, buffer, _decoderHandler);
        MetaData result = _decoderHandler.getMetaData();
        assertThat(result.getFields(), is(httpFields));
        assertThat(_decoderHandler.getInstruction(), instanceOf(SectionAcknowledgmentInstruction.class));
        assertTrue(_decoderHandler.isEmpty());

        _encoderInstructionParser.parse(QpackTestUtil.toBuffer(List.of(new SectionAcknowledgmentInstruction(streamId))));

        // B.2. Dynamic Table.

        // Set capacity to 220.
        _encoder.setCapacity(220);
        Instruction instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(SetCapacityInstruction.class));
        assertThat(((SetCapacityInstruction)instruction).getCapacity(), is(220));
        assertThat(QpackTestUtil.toHexString(instruction), QpackTestUtil.equalsHex("3fbd01"));

        _decoderInstructionParser.parse(QpackTestUtil.hexToBuffer("3fbd01"));
        assertThat(_decoder.getQpackContext().getDynamicTable().getCapacity(), is(220));

        // Insert with named referenced to static table. Test we get two instructions generated to add to the dynamic table.
        streamId = 4;
        httpFields = HttpFields.build()
            .add(":authority", "www.example.com")
            .add(":path", "/sample/path");
        buffer.clear();
        _encoder.encode(buffer, streamId, new MetaData(HttpVersion.HTTP_3, httpFields));
        buffer.flip();

        instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(IndexedNameEntryInstruction.class));
        assertThat(((IndexedNameEntryInstruction)instruction).getIndex(), is(0));
        assertThat(((IndexedNameEntryInstruction)instruction).getValue(), is("www.example.com"));
        assertThat(QpackTestUtil.toHexString(instruction), QpackTestUtil.equalsHex("c00f 7777 772e 6578 616d 706c 652e 636f 6d"));

        instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(IndexedNameEntryInstruction.class));
        assertThat(((IndexedNameEntryInstruction)instruction).getIndex(), is(1));
        assertThat(((IndexedNameEntryInstruction)instruction).getValue(), is("/sample/path"));
        assertThat(QpackTestUtil.toHexString(instruction), QpackTestUtil.equalsHex("c10c 2f73 616d 706c 652f 7061 7468"));
        assertTrue(_encoderHandler.isEmpty());

        // We cannot decode the buffer until we parse the two instructions generated above (we reach required insert count).
        _decoder.decode(streamId, buffer, _decoderHandler);
        assertNull(_decoderHandler.getMetaData());

        _decoderInstructionParser.parse(QpackTestUtil.hexToBuffer("c00f 7777 772e 6578 616d 706c 652e 636f 6d"));
        assertNull(_decoderHandler.getMetaData());
        assertThat(_decoderHandler.getInstruction(), instanceOf(InsertCountIncrementInstruction.class));

        _decoderInstructionParser.parse(QpackTestUtil.hexToBuffer("c10c 2f73 616d 706c 652f 7061 7468"));
        assertThat(_decoderHandler.getMetaData(), is(httpFields));
        assertThat(_decoderHandler.getInstruction(), instanceOf(InsertCountIncrementInstruction.class));

        assertThat(_decoderHandler.getInstruction(), instanceOf(SectionAcknowledgmentInstruction.class));
        assertTrue(_decoderHandler.isEmpty());

        // Parse the decoder instructions on the encoder.
        _encoderInstructionParser.parse(QpackTestUtil.toBuffer(List.of(new InsertCountIncrementInstruction(2))));
        _encoderInstructionParser.parse(QpackTestUtil.toBuffer(List.of(new SectionAcknowledgmentInstruction(streamId))));

        // B.3. Speculative Insert
        _encoder.insert(new HttpField("custom-key", "custom-value"));
        instruction = _encoderHandler.getInstruction();
        assertThat(instruction, instanceOf(LiteralNameEntryInstruction.class));
        assertThat(QpackTestUtil.toHexString(instruction), QpackTestUtil.equalsHex("4a63 7573 746f 6d2d 6b65 790c 6375 7374 6f6d 2d76 616c 7565"));
        _encoder.getInstructionHandler().onInsertCountIncrement(1);
    }
}
