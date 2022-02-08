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

package org.eclipse.jetty.server.session;

import java.io.Serializable;

/**
 * TestFoo
 */
public class TestFoo implements Foo, Serializable
{
    private static final long serialVersionUID = 953717519120144555L;

    private int i = -99;

    @Override
    public int getInt()
    {
        return this.i;
    }

    @Override
    public void setInt(int i)
    {
        this.i = i;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj == null)
            return false;

        return (((Foo)obj).getInt() == getInt());
    }
}
