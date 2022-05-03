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

public class bool_pointer extends ByReference
{
    public bool_pointer()
    {
        this(0);
    }
    public bool_pointer(int v)
    {
        super(1);
        getPointer().setByte(0, (byte)v);
    }

    public boolean getValue()
    {
        return getPointer().getByte(0) != 0;
    }
}
