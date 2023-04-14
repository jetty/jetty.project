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

package org.eclipse.jetty.websocket.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.StatusCode;

/**
 * <p>Annotation for classes to be WebSocket endpoints.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value = ElementType.TYPE)
public @interface WebSocket
{
    /**
     * <p>The size of the buffer (in bytes) used to read from the network layer.</p>
     */
    int inputBufferSize() default -1;

    /**
     * <p>The maximum size of a binary message (in bytes).</p>
     * <p>Binary messages over this maximum will result the WebSocket connection
     * being closed with {@link StatusCode#MESSAGE_TOO_LARGE}.</p>
     */
    // TODO: is this enforced in read or also write?
    int maxBinaryMessageSize() default -1;

    /**
     * <p>The time in milliseconds that a WebSocket connection may be idle.</p>
     */
    int idleTimeout() default -1;

    /**
     * <p>The maximum size of a text message (in bytes).</p>
     * <p>Text messages over this maximum will result in the WebSocket connection
     * being closed with {@link StatusCode#MESSAGE_TOO_LARGE}.</p>
     */
    int maxTextMessageSize() default -1;

    /**
     * @return whether the invocation of methods of this class is
     * guaranteed to be non-blocking.
     */
    boolean nonBlocking() default false;
}
