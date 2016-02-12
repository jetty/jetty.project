//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.StatusCode;

/**
 * Tags a POJO as being a WebSocket class.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value =
{ ElementType.TYPE })
public @interface WebSocket
{
    /* NOTE TO OTHER DEVELOPERS: 
     * If you change any of these default values,
     * make sure you sync the values with WebSocketPolicy
     */
    
    /**
     * The size of the buffer used to read from the network layer.
     * <p>
     * Default: 4096 (4 K)
     */
    int inputBufferSize() default 4 * 1024;

    /**
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * <p>
     * Default: 65536 (64 K)
     */
    int maxBinaryMessageSize() default 64 * 1024;

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * <p>
     * Default: 300000 (ms)
     */
    int maxIdleTime() default 300_000;

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * <p>
     * Default: 65536 (64 K)
     */
    int maxTextMessageSize() default 64 * 1024;
    
    /**
     * The output frame buffering mode.
     * <p>
     * Default: {@link BatchMode#AUTO}
     */
    BatchMode batchMode() default BatchMode.AUTO;
}
