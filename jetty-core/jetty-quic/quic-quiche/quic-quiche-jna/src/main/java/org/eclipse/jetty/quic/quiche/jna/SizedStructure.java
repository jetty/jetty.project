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

import com.sun.jna.Structure;

public class SizedStructure<S extends Structure>
{
    private final S structure;
    private final size_t size;

    public SizedStructure(S structure, size_t size)
    {
        this.structure = structure;
        this.size = size;
    }

    public S getStructure()
    {
        return structure;
    }

    public size_t getSize()
    {
        return size;
    }
}
