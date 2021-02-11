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
import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IncomingDecoderStreamTest
{
    public static class DebugHandler implements EncoderInstructionParser.Handler
    {
        public Queue<Integer> setCapacities = new LinkedList<>();
        public Queue<Integer> duplicates = new LinkedList<>();
        public Queue<Entry> literalNameEntries = new LinkedList<>();
        public Queue<ReferencedEntry> referencedNameEntries = new LinkedList<>();

        public static class Entry
        {
            public Entry(String name, String value)
            {
                this.value = value;
                this.name = name;
            }

            String name;
            String value;
        }

        public static class ReferencedEntry
        {
            public ReferencedEntry(int index, boolean dynamic, String value)
            {
                this.index = index;
                this.dynamic = dynamic;
                this.value = value;
            }

            int index;
            boolean dynamic;
            String value;
        }

        @Override
        public void onSetDynamicTableCapacity(int capacity)
        {
            setCapacities.add(capacity);
        }

        @Override
        public void onDuplicate(int index)
        {
            duplicates.add(index);
        }

        @Override
        public void onInsertNameWithReference(int nameIndex, boolean isDynamicTableIndex, String value)
        {
            referencedNameEntries.add(new ReferencedEntry(nameIndex, isDynamicTableIndex, value));
        }

        @Override
        public void onInsertWithLiteralName(String name, String value)
        {
            literalNameEntries.add(new Entry(name, value));
        }

        public boolean isEmpty()
        {
            return setCapacities.isEmpty() && duplicates.isEmpty() && literalNameEntries.isEmpty() && referencedNameEntries.isEmpty();
        }
    }

    private EncoderInstructionParser _instructionParser;
    private DebugHandler _handler;

    @BeforeEach
    public void before()
    {
        _handler = new DebugHandler();
        _instructionParser = new EncoderInstructionParser(_handler);
    }

    @Test
    public void testAddWithReferencedEntry() throws Exception
    {
        String insertAuthorityEntry = "c00f7777772e6578616d706c652e636f6d";
        ByteBuffer buffer = BufferUtil.toBuffer(TypeUtil.fromHexString(insertAuthorityEntry));
        _instructionParser.parse(buffer);
        DebugHandler.ReferencedEntry entry = _handler.referencedNameEntries.poll();
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
