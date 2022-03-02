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

package org.eclipse.jetty.ee9.websocket.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.ee9.websocket.api.BatchMode;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;

/**
 * Tags a POJO as being a WebSocket class.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
    {ElementType.TYPE})
public @interface WebSocket
{
    /**
     * The size of the buffer (in bytes) used to read from the network layer.
     */
    int inputBufferSize() default -1;

    /**
     * The maximum size of a binary message (in bytes) during parsing/generating.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     */
    int maxBinaryMessageSize() default -1;

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     */
    int idleTimeout() default -1;

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     */
    int maxTextMessageSize() default -1;

    /**
     * The output frame buffering mode.
     * <p>
     * Default: {@link BatchMode#AUTO}
     */
    BatchMode batchMode() default BatchMode.AUTO;
}
