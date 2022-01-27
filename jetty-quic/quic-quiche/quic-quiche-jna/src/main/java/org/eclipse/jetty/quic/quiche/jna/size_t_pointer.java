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

import com.sun.jna.Native;
import com.sun.jna.ptr.ByReference;

public class size_t_pointer extends ByReference
{
    public size_t_pointer()
    {
        this((byte)0);
    }
    public size_t_pointer(long v)
    {
        super(Native.SIZE_T_SIZE);
        switch (Native.SIZE_T_SIZE)
        {
            case 4:
                getPointer().setInt(0, (int)v);
                break;
            case 8:
                getPointer().setLong(0, v);
                break;
            default:
                throw new AssertionError("Unsupported native size_t size: " + Native.SIZE_T_SIZE);
        }
    }

    public long getValue()
    {
        switch (Native.SIZE_T_SIZE)
        {
            case 4:
                return getPointer().getInt(0);
            case 8:
                return getPointer().getLong(0);
            default:
                throw new AssertionError("Unsupported native size_t size: " + Native.SIZE_T_SIZE);
        }
    }

    public size_t getPointee()
    {
        return new size_t(getValue());
    }
}
