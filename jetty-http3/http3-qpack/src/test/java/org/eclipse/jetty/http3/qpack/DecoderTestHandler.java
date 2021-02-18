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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.generator.Instruction;

public class DecoderTestHandler implements QpackDecoder.Handler
{
    private final Queue<MetaData> _metadataList = new LinkedList<>();
    private final Queue<Instruction> _instructionList = new LinkedList<>();

    @Override
    public void onMetadata(MetaData metaData)
    {
        _metadataList.add(metaData);
    }

    @Override
    public void onInstruction(Instruction instruction)
    {
        _instructionList.add(instruction);
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
