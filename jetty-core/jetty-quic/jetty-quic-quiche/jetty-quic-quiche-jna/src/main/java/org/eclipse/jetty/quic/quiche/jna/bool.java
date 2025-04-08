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

package org.eclipse.jetty.quic.quiche.jna;

import com.sun.jna.IntegerType;

public class bool extends IntegerType
{
    public bool()
    {
        this(false);
    }

    public bool(boolean v)
    {
        // In Rust, the size of bool is 1 byte, see https://github.com/rust-lang/rust/pull/46176#issuecomment-359593446
        super(1, v ? 1 : 0, true);
    }

    public boolean isTrue()
    {
        return this.byteValue() != 0;
    }

    public boolean isFalse()
    {
        return !isTrue();
    }
}
