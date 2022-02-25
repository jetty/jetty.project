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

package org.eclipse.jetty.util.ajax;

public class Baz
{
    private String _message;
    private Foo _foo;
    private Boolean _boolean2;

    public Baz()
    {
        // Required by the POJO convertor.
    }

    public Baz(String message, Boolean boolean2, Foo foo)
    {
        setMessage(message);
        setBoolean2(boolean2);
        setFoo(foo);
    }

    // Getters and setters required by the POJO convertor.

    public void setMessage(String message)
    {
        _message = message;
    }

    public String getMessage()
    {
        return _message;
    }

    public void setFoo(Foo foo)
    {
        _foo = foo;
    }

    public Foo getFoo()
    {
        return _foo;
    }

    public void setBoolean2(Boolean boolean2)
    {
        _boolean2 = boolean2;
    }

    public Boolean isBoolean2()
    {
        return _boolean2;
    }

    @Override
    public String toString()
    {
        return "\n=== " + getClass().getSimpleName() + " ===" +
            "\nmessage: " + getMessage() +
            "\nboolean2: " + isBoolean2() +
            "\nfoo: " + getFoo();
    }
}
