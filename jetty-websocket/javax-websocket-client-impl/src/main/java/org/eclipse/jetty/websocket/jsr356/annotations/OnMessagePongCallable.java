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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import javax.websocket.OnMessage;
import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.jsr356.JsrPongMessage;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link OnMessage} annotated methods with a {@link PongMessage} message object.
 */
public class OnMessagePongCallable extends OnMessageCallable
{
    public OnMessagePongCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);
    }

    /**
     * Copy Constructor
     * @param copy the callable to copy from
     */
    public OnMessagePongCallable(OnMessageCallable copy)
    {
        super(copy);
    }

    public Object call(Object endpoint, ByteBuffer buf)
    {
        super.args[idxMessageObject] = new JsrPongMessage(buf);
        return super.call(endpoint,super.args);
    }

    @Override
    public void init(JsrSession session)
    {
        idxMessageObject = findIndexForRole(Role.MESSAGE_PONG);
        assertRoleRequired(idxMessageObject,"Pong Message Object");
        super.init(session);
    }
}
