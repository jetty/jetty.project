//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.websocket.javax.common.util.InvokerUtils;
import org.eclipse.jetty.websocket.javax.common.util.ReflectUtils;

public class CompletableFutureMethodHandle
{
    public static <T> MethodHandle of(Class<T> type, CompletableFuture<T> future)
    {
        Method method = ReflectUtils.findMethod(CompletableFuture.class, "complete", type);
        MethodHandle completeHandle = InvokerUtils.mutatedInvoker(CompletableFuture.class, method, new InvokerUtils.Arg(type));
        return completeHandle.bindTo(future);
    }
}
