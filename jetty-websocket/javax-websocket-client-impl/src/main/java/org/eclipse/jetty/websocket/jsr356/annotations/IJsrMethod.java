//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.lang.reflect.Method;
import javax.websocket.Decoder;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.jsr356.MessageType;

public interface IJsrMethod
{
    /**
     * Indicate that partial message support is desired
     */
    void enablePartialMessageSupport();

    /**
     * Get the fully qualifed method name {classname}.{methodname}({params}) suitable for using in error messages.
     *
     * @return the fully qualified method name for end users
     */
    String getFullyQualifiedMethodName();

    /**
     * Get the Decoder to use for message decoding
     *
     * @return the decoder class to use for message decoding
     */
    Class<? extends Decoder> getMessageDecoder();

    /**
     * The type of message this method can handle
     *
     * @return the message type if &#064;{@link OnMessage} annotated, null if unknown/unspecified
     */
    MessageType getMessageType();

    /**
     * The reflected method
     *
     * @return the method itself
     */
    Method getMethod();

    /**
     * Indicator that partial message support is enabled
     *
     * @return true if enabled
     */
    boolean isPartialMessageSupportEnabled();

    /**
     * The message decoder class to use.
     *
     * @param decoderClass the {@link Decoder} implementation to use
     */
    void setMessageDecoder(Class<? extends Decoder> decoderClass);

    /**
     * The type of message this method can handle
     *
     * @param type the type of message
     */
    void setMessageType(MessageType type);
}
