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

import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.generator.Instruction;

public class TestEncoderHandler implements QpackEncoder.Handler
{
    private final Queue<Instruction> _instructionList = new LinkedList<>();
    private QpackDecoder _decoder;

    public void setDecoder(QpackDecoder decoder)
    {
        _decoder = decoder;
    }

    @Override
    public void onInstruction(Instruction instruction) throws QpackException
    {
        _instructionList.add(instruction);
        if (_decoder != null)
            _decoder.parseInstruction(QpackTestUtil.toBuffer(instruction));
    }

    public Instruction getInstruction()
    {
        return _instructionList.poll();
    }

    public boolean isEmpty()
    {
        return _instructionList.isEmpty();
    }
}
