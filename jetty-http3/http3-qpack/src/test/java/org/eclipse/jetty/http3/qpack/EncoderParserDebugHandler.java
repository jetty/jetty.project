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

import org.eclipse.jetty.http3.qpack.internal.parser.EncoderInstructionParser;

public class EncoderParserDebugHandler implements EncoderInstructionParser.Handler
{
    public Queue<Integer> sectionAcknowledgements = new LinkedList<>();
    public Queue<Integer> streamCancellations = new LinkedList<>();
    public Queue<Integer> insertCountIncrements = new LinkedList<>();

    private final QpackEncoder _encoder;

    public EncoderParserDebugHandler()
    {
        this(null);
    }

    public EncoderParserDebugHandler(QpackEncoder encoder)
    {
        _encoder = encoder;
    }

    @Override
    public void onSectionAcknowledgement(int streamId) throws QpackException
    {
        sectionAcknowledgements.add(streamId);
        if (_encoder != null)
            _encoder.sectionAcknowledgement(streamId);
    }

    @Override
    public void onStreamCancellation(int streamId) throws QpackException
    {
        streamCancellations.add(streamId);
        if (_encoder != null)
            _encoder.streamCancellation(streamId);
    }

    @Override
    public void onInsertCountIncrement(int increment) throws QpackException
    {
        insertCountIncrements.add(increment);
        if (_encoder != null)
            _encoder.insertCountIncrement(increment);
    }

    public boolean isEmpty()
    {
        return sectionAcknowledgements.isEmpty() && streamCancellations.isEmpty() && insertCountIncrements.isEmpty();
    }
}
