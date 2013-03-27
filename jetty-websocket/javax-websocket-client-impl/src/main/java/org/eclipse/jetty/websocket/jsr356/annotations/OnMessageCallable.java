//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.utils.MethodUtils;

public abstract class OnMessageCallable extends JsrCallable
{
    protected int idxPartialMessageFlag = -1;
    protected int idxMessageObject = -1;
    protected Param.Role messageRole;

    public OnMessageCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);
    }

    protected void assertDecoderRequired()
    {
        if (getDecoder() == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to find a valid ");
            err.append(Decoder.class.getName());
            err.append(" for parameter #");
            Param param = params[idxMessageObject];
            err.append(param.index);
            err.append(" [").append(param.type).append("] in method: ");
            err.append(MethodUtils.toString(pojo,method));
            throw new InvalidSignatureException(err.toString());
        }
    }

    protected void assertRoleRequired(int index, String description)
    {
        if (index < 0)
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to find parameter with role [");
            err.append(description).append("] in method: ");
            err.append(MethodUtils.toString(pojo,method));
            throw new InvalidSignatureException(err.toString());
        }
    }

    public boolean isPartialMessageSupported()
    {
        return (idxPartialMessageFlag >= 0);
    }

    public void setPartialMessageFlag(Param param)
    {
        idxPartialMessageFlag = param.index;
    }
}
