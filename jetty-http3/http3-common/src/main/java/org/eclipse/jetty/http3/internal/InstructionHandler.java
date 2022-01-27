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

package org.eclipse.jetty.http3.internal;

import java.util.List;

import org.eclipse.jetty.http3.qpack.Instruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstructionHandler implements Instruction.Handler
{
    private static final Logger LOG = LoggerFactory.getLogger(InstructionHandler.class);

    private final InstructionFlusher encoderFlusher;

    public InstructionHandler(InstructionFlusher encoderFlusher)
    {
        this.encoderFlusher = encoderFlusher;
    }

    @Override
    public void onInstructions(List<Instruction> instructions)
    {
        if (encoderFlusher.offer(instructions))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("processing {}", instructions);
            encoderFlusher.iterate();
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not process {}", instructions);
        }
    }
}
