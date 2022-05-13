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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.QpackException.SessionException;
import org.eclipse.jetty.http3.qpack.internal.instruction.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http3.qpack.QpackTestUtil.encode;
import static org.eclipse.jetty.http3.qpack.QpackTestUtil.toBuffer;
import static org.eclipse.jetty.http3.qpack.QpackTestUtil.toMetaData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SectionAcknowledgmentTest
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
    public void testSectionAcknowledgmentForZeroRequiredInsertCountOnDecoder() throws Exception
    {
        // Encode a header with only a value contained in the static table.
        ByteBuffer buffer = encode(_encoder, 0, toMetaData("GET", "/", "http"));

        // No instruction since no addition to table.
        Instruction instruction = _encoderHandler.getInstruction();
        assertNull(instruction);

        // Decoding should generate no instruction.
        _decoder.decode(0, buffer, _decoderHandler);
        instruction = _decoderHandler.getInstruction();
        assertNull(instruction);
    }

    @Test
    public void testSectionAcknowledgmentForZeroRequiredInsertCountOnEncoder() throws Exception
    {
        // Encode a header with only a value contained in the static table.
        ByteBuffer buffer = encode(_encoder, 0, toMetaData("GET", "/", "http"));
        assertThat(BufferUtil.remaining(buffer), greaterThan(0L));

        // Parsing a section ack instruction on the encoder when we are not expecting it should result in QPACK_DECODER_STREAM_ERROR.
        SectionAcknowledgmentInstruction instruction = new SectionAcknowledgmentInstruction(0);
        ByteBuffer instructionBuffer = toBuffer(instruction);
        SessionException error = assertThrows(SessionException.class, () -> _encoder.parseInstructions(instructionBuffer));
        assertThat(error.getErrorCode(), equalTo(QpackException.QPACK_ENCODER_STREAM_ERROR));
        assertThat(error.getMessage(), containsString("No StreamInfo for 0"));
    }
}
