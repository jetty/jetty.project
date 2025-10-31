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

import java.io.Reader;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Annotation for methods to receive BINARY or TEXT WebSocket events.</p>
 * <p>Acceptable method signatures for text messages:</p>
 * <ol>
 * <li>{@code public void methodName(Session session, *String text)}</li>
 * <li>{@code public void methodName(Session session, *Reader reader)}</li>
 * </ol>
 * <p>The {@code *} before the parameter type means that the parameter is mandatory.</p>
 * <p>NOTE</p>
 * <p>The {@link Reader} argument will always use the UTF-8 charset,
 * (as dictated by RFC 6455). If you need to use a different charset,
 * you must use BINARY messages.</p>
 * <p>Acceptable method signatures for binary messages:</p>
 * <ol>
 * <li>{@code public void methodName(Session session, *ByteBuffer message, *Callback callback)}</li>
 * <li>{@code public void methodName(Session session, *InputStream stream)}</li>
 * </ol>
 * <p>The {@code *} before the parameter type means that the parameter is mandatory.</p>
 * <p>Partial messages are used to receive individual frames (and therefore partial
 * messages) without aggregating the frames into a complete WebSocket message.
 * A {@code boolean} parameter is supplied to indicate whether the frame is
 * the last segment of data of the message.</p>
 * <p>Acceptable method signatures for partial messages:</p>
 * <ol>
 * <li>{@code public void methodName(Session session, *String payload, *boolean last)}</li>
 * <li>{@code public void methodName(Session session, *ByteBuffer payload, *boolean last, *Callback callback)}</li>
 * </ol>
 * <p>The {@code *} before the parameter type means that the parameter is mandatory.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnWebSocketMessage
{
}
