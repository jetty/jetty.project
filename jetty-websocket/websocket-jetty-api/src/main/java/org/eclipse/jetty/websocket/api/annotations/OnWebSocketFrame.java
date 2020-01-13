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

package org.eclipse.jetty.websocket.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;

/**
 * (ADVANCED) Annotation for tagging methods to receive frame events.
 * <p>
 * Acceptable method patterns.<br>
 * Note: {@code methodName} can be any name you want to use.
 * <ol>
 * <li><code>public void methodName({@link Frame} frame)</code></li>
 * <li><code>public void methodName({@link Session} session, {@link Frame} frame)</code></li>
 * </ol>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
    {ElementType.METHOD})
public @interface OnWebSocketFrame
{
    /* no config */
}
