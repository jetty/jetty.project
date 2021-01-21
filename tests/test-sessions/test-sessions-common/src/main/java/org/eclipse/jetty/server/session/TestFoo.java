//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
