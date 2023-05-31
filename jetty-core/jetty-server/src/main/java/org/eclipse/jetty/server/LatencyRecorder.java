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

package org.eclipse.jetty.server;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.LongConsumer;

/**
 * <p>A marker interface whose implementation records the latency of the executed requests when registered
 * as a bean on a {@link Server} instance.</p>
 * <p>The reported latency is the delay between {@link Request#getNanoTime() the first notice of the request} and
 * {@link HttpChannel.Listener#onComplete(Request, Throwable) when it has completed}.</p>
 */
@FunctionalInterface
public interface LatencyRecorder extends LongConsumer
{
    /**
     * When a {@link LatencyRecorder} is annotated as legacy, the reported latency is the delay between
     * {@link HttpChannel.Listener#onRequestBegin(Request) the beginning of the request} and
     * {@link HttpChannel.Listener#onComplete(Request, Throwable) when it has completed}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Legacy
    {
    }
}
