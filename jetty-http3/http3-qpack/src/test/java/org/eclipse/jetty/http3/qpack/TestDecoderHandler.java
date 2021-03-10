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

import org.eclipse.jetty.http.HttpFields;

public class TestDecoderHandler implements QpackDecoder.Handler
{
    private final Queue<HttpFields> _httpFieldsList = new LinkedList<>();
    private final Queue<Instruction> _instructionList = new LinkedList<>();
    private QpackEncoder _encoder;

    public void setEncoder(QpackEncoder encoder)
    {
        _encoder = encoder;
    }

    @Override
    public void onHttpFields(int streamId, HttpFields httpFields)
    {
        _httpFieldsList.add(httpFields);
    }

    @Override
    public void onInstruction(Instruction instruction) throws QpackException
    {
        _instructionList.add(instruction);
        if (_encoder != null)
            _encoder.parseInstruction(QpackTestUtil.toBuffer(instruction));
    }

    public HttpFields getHttpFields()
    {
        return _httpFieldsList.poll();
    }

    public Instruction getInstruction()
    {
        return _instructionList.poll();
    }

    public boolean isEmpty()
    {
        return _httpFieldsList.isEmpty() && _instructionList.isEmpty();
    }
}
