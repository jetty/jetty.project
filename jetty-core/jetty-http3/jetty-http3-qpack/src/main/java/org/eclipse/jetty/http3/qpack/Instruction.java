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

import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;

public interface Instruction
{
    void encode(ByteBufferPool.Lease lease);

    /**
     * <p>A handler for instructions issued by an {@link QpackEncoder} or {@link QpackDecoder}.</p>
     * <p>Note: an encoder SHOULD NOT write an instruction unless sufficient stream and connection flow control
     * credit is available for the entire instruction, otherwise a stream containing a large instruction can become
     * deadlocked.</p>
     */
    interface Handler
    {
        void onInstructions(List<Instruction> instructions);
    }
}
