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

package org.eclipse.jetty.quic.common;

import org.eclipse.jetty.io.RetainableByteBuffer;

// TODO: consider making this class non-internal to be able to write a proxy.
/// A record for QUIC packet header and payload buffers.
///
/// Both buffers must be released after they have been processed.
///
/// @param header the packet header buffer
/// @param payload the packet payload buffer
public record PacketBuffers(RetainableByteBuffer header, RetainableByteBuffer payload)
{
    public interface Listener
    {
        boolean onPacketBuffers(PacketBuffers buffers);
    }
}
