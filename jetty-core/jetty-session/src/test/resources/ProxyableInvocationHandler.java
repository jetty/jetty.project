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

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class ProxyableInvocationHandler implements InvocationHandler, Serializable
{
    private static final long serialVersionUID = 222222222222L;

    public ProxyableInvocationHandler()
    {
        super();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        if ("equals".equals(method.getName()))
        {
            if (args != null && (args[0] instanceof Proxyable))
                return true;
            else
                return false;
        }

        return 5;
    }
}
