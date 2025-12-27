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

package org.eclipse.jetty.quic.common.internal.packets;

// TODO: this abstraction is necessary because
//  encoding packet numbers requires to know the largets acked
//  packet number, and decoding them requires the largest acked too.
//  So it needs to store the largest acked too, and also
//  managed packet number spaces, that are different from
//  EncryptionLevels.
public class PacketNumbers
{
    public PacketNumber newPacketNumber(long packetNumber)
    {
        return new PacketNumber(2, 2, 4);
    }
}
