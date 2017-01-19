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

import javax.websocket.Decoder;
import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.EncoderFactory;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

public class OnMessageCallable extends JsrCallable
{
    protected final Class<?> returnType;
    protected Encoder returnEncoder;
    protected Class<?> decodingType;
    protected Decoder decoder;
    protected int idxPartialMessageFlag = -1;
    protected int idxMessageObject = -1;
    protected boolean messageRoleAssigned = false;

    public OnMessageCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);
        this.returnType = method.getReturnType();
    }

    public OnMessageCallable(OnMessageCallable copy)
    {
        super(copy);
        this.returnType = copy.returnType;
        this.decodingType = copy.decodingType;
        this.decoder = copy.decoder;
        this.idxPartialMessageFlag = copy.idxPartialMessageFlag;
        this.idxMessageObject = copy.idxMessageObject;
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
            err.append(ReflectUtils.toString(pojo,method));
            throw new InvalidSignatureException(err.toString());
        }
    }

    private int findMessageObjectIndex()
    {
        int index = -1;

        for (Param.Role role : Param.Role.getMessageRoles())
        {
            index = findIndexForRole(role);
            if (index >= 0)
            {
                return index;
            }
        }

        return -1;
    }

    public Decoder getDecoder()
    {
        return decoder;
    }

    public Param getMessageObjectParam()
    {
        if (idxMessageObject < 0)
        {
            idxMessageObject = findMessageObjectIndex();

            if (idxMessageObject < 0)
            {
                StringBuilder err = new StringBuilder();
                err.append("A message type must be specified [TEXT, BINARY, DECODER, or PONG] : ");
                err.append(ReflectUtils.toString(pojo,method));
                throw new InvalidSignatureException(err.toString());
            }
        }

        return super.params[idxMessageObject];
    }

    public Encoder getReturnEncoder()
    {
        return returnEncoder;
    }

    public Class<?> getReturnType()
    {
        return returnType;
    }

    @Override
    public void init(JsrSession session)
    {
        super.init(session);
        idxPartialMessageFlag = findIndexForRole(Role.MESSAGE_PARTIAL_FLAG);

        EncoderFactory.Wrapper encoderWrapper = session.getEncoderFactory().getWrapperFor(returnType);
        if (encoderWrapper != null)
        {
            this.returnEncoder = encoderWrapper.getEncoder();
        }

        if (decodingType != null)
        {
            this.decoder = session.getDecoderFactory().getDecoderFor(decodingType);
        }
    }

    public boolean isMessageRoleAssigned()
    {
        return messageRoleAssigned;
    }

    public boolean isPartialMessageSupported()
    {
        return (idxPartialMessageFlag >= 0);
    }

    @Override
    public void setDecodingType(Class<?> decodingType)
    {
        this.decodingType = decodingType;
        messageRoleAssigned = true;
    }

    public void setPartialMessageFlag(Param param)
    {
        idxPartialMessageFlag = param.index;
    }
}
