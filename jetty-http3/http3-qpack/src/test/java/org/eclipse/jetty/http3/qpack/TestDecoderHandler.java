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
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http.MetaData;

public class TestDecoderHandler implements QpackDecoder.Handler, Instruction.Handler
{
    private final Queue<MetaData> _metadataList = new LinkedList<>();
    private final Queue<Instruction> _instructionList = new LinkedList<>();
    private QpackEncoder _encoder;

    public void setEncoder(QpackEncoder encoder)
    {
        _encoder = encoder;
    }

    @Override
    public void onMetaData(long streamId, MetaData metadata)
    {
        _metadataList.add(metadata);
    }

    @Override
    public void onInstructions(List<Instruction> instructions) throws QpackException
    {
        _instructionList.addAll(instructions);
        if (_encoder != null)
            _encoder.parseInstruction(QpackTestUtil.toBuffer(instructions));
    }

    public MetaData getMetaData()
    {
        return _metadataList.poll();
    }

    public Instruction getInstruction()
    {
        return _instructionList.poll();
    }

    public boolean isEmpty()
    {
        return _metadataList.isEmpty() && _instructionList.isEmpty();
    }
}
