//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;

public class InstructionGeneratorTest
{
    private final ByteBufferPool _bufferPool = ByteBufferPool.NON_POOLING;

    private String toHexString(Instruction instruction)
    {
        RetainableByteBuffer.DynamicCapacity lease = new RetainableByteBuffer.DynamicCapacity();
        instruction.encode(_bufferPool, lease);
        return BufferUtil.toHexString(lease.getByteBuffer());
    }

    @Test
    public void testDecoderInstructions() throws Exception
    {
        Instruction instruction;

        instruction = new SectionAcknowledgmentInstruction(4);
        assertThat(toHexString(instruction), equalToIgnoringCase("84"));

        instruction = new SectionAcknowledgmentInstruction(1337);
        assertThat(toHexString(instruction), equalToIgnoringCase("FFBA09"));
    }

    @Test
    public void testEncoderInstructions() throws Exception
    {
        Instruction instruction;

        instruction = new IndexedNameEntryInstruction(false, 0, false, "www.example.com");
        assertThat(toHexString(instruction), equalToIgnoringCase("c00f7777772e6578616d706c652e636f6d"));

        instruction = new IndexedNameEntryInstruction(false, 1, false, "/sample/path");
        assertThat(toHexString(instruction), equalToIgnoringCase("c10c2f73616d706c652f70617468"));
    }
}
