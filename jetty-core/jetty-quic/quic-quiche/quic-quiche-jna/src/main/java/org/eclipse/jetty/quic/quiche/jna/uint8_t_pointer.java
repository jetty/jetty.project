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

package org.eclipse.jetty.quic.quiche.jna;

import com.sun.jna.ptr.ByReference;

public class uint8_t_pointer extends ByReference
{
    public uint8_t_pointer()
    {
        this((byte)0);
    }
    public uint8_t_pointer(byte v)
    {
        super(1);
        getPointer().setByte(0, v);
    }

    public byte getValue()
    {
        return getPointer().getByte(0);
    }

    public uint8_t getPointee()
    {
        return new uint8_t(getValue());
    }
}
