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

import org.eclipse.jetty.http3.qpack.internal.parser.DecoderInstructionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DecoderInstructionParserTest
{
    private DecoderInstructionParser _instructionParser;
    private DecoderParserDebugHandler _handler;

    @BeforeEach
    public void before()
    {
        _handler = new DecoderParserDebugHandler();
        _instructionParser = new DecoderInstructionParser(_handler);
    }

    @Test
    public void testSetDynamicTableCapacityInstruction() throws Exception
    {
        // Set Dynamic Table Capacity=220.
        ByteBuffer buffer = QpackTestUtil.hexToBuffer("3fbd 01");
        _instructionParser.parse(buffer);
        assertThat(_handler.setCapacities.poll(), is(220));
        assertTrue(_handler.isEmpty());
    }

    @Test
    public void testDuplicateInstruction() throws Exception
    {
        // Duplicate (Relative Index = 2).
        ByteBuffer buffer = QpackTestUtil.hexToBuffer("02");
        _instructionParser.parse(buffer);
        assertThat(_handler.duplicates.poll(), is(2));
        assertTrue(_handler.isEmpty());
    }

    @Test
    public void testInsertNameWithReferenceInstruction() throws Exception
    {
        // Insert With Name Reference to Static Table, Index=0 (:authority=www.example.com).
        ByteBuffer buffer = QpackTestUtil.hexToBuffer("c00f 7777 772e 6578 616d 706c 652e 636f 6d");
        _instructionParser.parse(buffer);
        DecoderParserDebugHandler.ReferencedEntry entry = _handler.referencedNameEntries.poll();
        assertNotNull(entry);
        assertThat(entry.index, is(0));
        assertThat(entry.dynamic, is(false));
        assertThat(entry.value, is("www.example.com"));

        // Insert With Name Reference to Static Table, Index=1 (:path=/sample/path).
        buffer = QpackTestUtil.hexToBuffer("c10c 2f73 616d 706c 652f 7061 7468");
        _instructionParser.parse(buffer);
        entry = _handler.referencedNameEntries.poll();
        assertNotNull(entry);
        assertThat(entry.index, is(1));
        assertThat(entry.dynamic, is(false));
        assertThat(entry.value, is("/sample/path"));
    }

    @Test
    public void testInsertWithLiteralNameInstruction() throws Exception
    {
        // Insert With Literal Name (custom-key=custom-value).
        ByteBuffer buffer = QpackTestUtil.hexToBuffer("4a63 7573 746f 6d2d 6b65 790c 6375 7374 6f6d 2d76 616c 7565");
        _instructionParser.parse(buffer);

        // We received the instruction correctly.
        DecoderParserDebugHandler.LiteralEntry entry = _handler.literalNameEntries.poll();
        assertNotNull(entry);
        assertThat(entry.name, is("custom-key"));
        assertThat(entry.value, is("custom-value"));

        // There are no other instructions received.
        assertTrue(_handler.isEmpty());
    }
}
