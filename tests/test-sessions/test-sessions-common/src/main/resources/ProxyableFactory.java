//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import java.lang.reflect.Proxy;

public class ProxyableFactory
{
    public static Proxyable newProxyable(ClassLoader cl)
    {
        return getProxy(cl, new ProxyableInvocationHandler());
    }

    public static Proxyable getProxy(ClassLoader classloader, ProxyableInvocationHandler pih)
    {
        Proxyable p = (Proxyable)Proxy.newProxyInstance(classloader, new Class[]{Proxyable.class}, pih);
        return p;
    }
}
