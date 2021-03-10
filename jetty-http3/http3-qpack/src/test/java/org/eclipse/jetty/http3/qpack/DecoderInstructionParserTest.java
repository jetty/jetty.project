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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
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
    public void testAddWithReferencedEntry() throws Exception
    {
        String insertAuthorityEntry = "c00f7777772e6578616d706c652e636f6d";
        ByteBuffer buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(insertAuthorityEntry));
        _instructionParser.parse(buffer);
        DecoderParserDebugHandler.ReferencedEntry entry = _handler.referencedNameEntries.poll();
        assertNotNull(entry);
        assertThat(entry.index, is(0));
        assertThat(entry.dynamic, is(false));
        assertThat(entry.value, is("www.example.com"));

        String insertPathEntry = "c10c2f73616d706c652f70617468";
        buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(insertPathEntry));
        _instructionParser.parse(buffer);
        entry = _handler.referencedNameEntries.poll();
        assertNotNull(entry);
        assertThat(entry.index, is(1));
        assertThat(entry.dynamic, is(false));
        assertThat(entry.value, is("/sample/path"));
    }

    @Test
    public void testSetCapacity() throws Exception
    {
        String setCapacityString = "3fbd01";
        ByteBuffer buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(setCapacityString));
        _instructionParser.parse(buffer);
        assertThat(_handler.setCapacities.poll(), is(220));
        assertTrue(_handler.isEmpty());
    }
}
