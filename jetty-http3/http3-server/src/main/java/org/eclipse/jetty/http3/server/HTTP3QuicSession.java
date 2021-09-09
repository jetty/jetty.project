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

package org.eclipse.jetty.http3.server;

import java.util.List;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicSession;

public class HTTP3QuicSession extends ProtocolQuicSession
{
    private final QpackDecoder decoder;
    private final Instruction.Handler decoderHandler = new QpackDecoderInstructionHandler();

    public HTTP3QuicSession(QuicSession session, int maxHeaderSize)
    {
        super(session);
        decoder = new QpackDecoder(decoderHandler, maxHeaderSize);
        // TODO: create a streamId for the Instruction stream.
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    private class QpackDecoderInstructionHandler implements Instruction.Handler
    {
        @Override
        public void onInstructions(List<Instruction> instructions) throws QpackException
        {
            // TODO: feed the Instruction to QuicSession.
        }
    }
}
