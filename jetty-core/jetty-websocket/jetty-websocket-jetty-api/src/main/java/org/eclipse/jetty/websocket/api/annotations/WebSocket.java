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
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.Session;

/**
 * <p>Annotation for classes to be WebSocket endpoints.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value = ElementType.TYPE)
public @interface WebSocket
{
    /**
     * <p>Returns whether demand for WebSocket frames is automatically performed
     * upon successful return from methods annotated with {@link OnWebSocketOpen},
     * {@link OnWebSocketFrame} and {@link OnWebSocketMessage}.</p>
     * <p>If the demand is not automatic, then {@link Session#demand()} must be
     * explicitly invoked to receive more WebSocket frames (both control and
     * data frames, including CLOSE frames).</p>
     *
     * @return whether demand for WebSocket frames is automatic
     */
    boolean autoDemand() default true;
}
