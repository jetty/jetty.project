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

package org.eclipse.jetty.websocket.jmh;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class MetafactoryTest
{

    @FunctionalInterface
    public interface Test
    {
        Object get();
    }

    public static void main(String[] args) throws Throwable
    {

        MethodHandles.Lookup caller = MethodHandles.lookup();
        MethodHandle methodHandle = caller.findStatic(MetafactoryTest.class, "print", MethodType.methodType(Object.class, String.class));
        MethodType invokedType = MethodType.methodType(Test.class);
        CallSite site = LambdaMetafactory.metafactory(caller,
            "get",
            invokedType,
            methodHandle.type(),
            methodHandle,
            methodHandle.type());
        MethodHandle factory = site.getTarget();
        Test r = (Test)factory.invoke();
        System.err.println(r.get());
    }

    private static Object print(String s)
    {
        return "hello world";
    }

}