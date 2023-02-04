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

import org.eclipse.jetty.http3.qpack.internal.instruction.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SectionAcknowledgmentInstruction;
import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;

public class InstructionGeneratorTest
{
    private final ByteBufferPool _bufferPool = new ArrayRetainableByteBufferPool();

    private String toHexString(Instruction instruction)
    {
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        instruction.encode(accumulator);
        assertThat(accumulator.getSize(), is(1));
        return BufferUtil.toHexString(accumulator.getByteBuffers().get(0));
    }

    @Test
    public void testDecoderInstructions() throws Exception
    {
        Instruction instruction;

        instruction = new SectionAcknowledgmentInstruction(_bufferPool, 4);
        assertThat(toHexString(instruction), equalToIgnoringCase("84"));

        instruction = new SectionAcknowledgmentInstruction(_bufferPool, 1337);
        assertThat(toHexString(instruction), equalToIgnoringCase("FFBA09"));
    }

    @Test
    public void testEncoderInstructions() throws Exception
    {
        Instruction instruction;

        instruction = new IndexedNameEntryInstruction(_bufferPool, false, 0, false, "www.example.com");
        assertThat(toHexString(instruction), equalToIgnoringCase("c00f7777772e6578616d706c652e636f6d"));

        instruction = new IndexedNameEntryInstruction(_bufferPool, false, 1, false, "/sample/path");
        assertThat(toHexString(instruction), equalToIgnoringCase("c10c2f73616d706c652f70617468"));
    }
}
