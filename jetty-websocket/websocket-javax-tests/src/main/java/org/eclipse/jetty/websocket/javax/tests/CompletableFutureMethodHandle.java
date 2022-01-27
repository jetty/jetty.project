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

package org.eclipse.jetty.websocket.javax.tests;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerFactory;

public class CompletableFutureMethodHandle
{
    public static <T> MethodHandle of(Class<T> type, CompletableFuture<T> future)
    {
        Method method = ReflectUtils.findMethod(CompletableFuture.class, "complete", type);
        MethodHandles.Lookup lookup = JavaxWebSocketFrameHandlerFactory.getServerMethodHandleLookup();
        MethodHandle completeHandle = InvokerUtils.mutatedInvoker(lookup, CompletableFuture.class, method, new InvokerUtils.Arg(type));
        return completeHandle.bindTo(future);
    }
}
