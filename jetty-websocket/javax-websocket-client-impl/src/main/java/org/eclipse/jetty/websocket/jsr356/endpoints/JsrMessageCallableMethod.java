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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.decoders.Decoders;
import org.eclipse.jetty.websocket.jsr356.encoders.Encoders;
import org.eclipse.jetty.websocket.jsr356.utils.DeploymentTypeUtils;

public class JsrMessageCallableMethod extends CallableMethod
{
    private static class ParamRef
    {
        int index;
        Class<?> type;
        Decoder decoder;

        public ParamRef(int idx, Class<?> type)
        {
            this.index = idx;
            this.type = type;
        }
    }

    public static boolean isBinaryFormat(Class<?> type)
    {
        return ByteBuffer.class.isAssignableFrom(type) || byte[].class.isAssignableFrom(type) || InputStream.class.isAssignableFrom(type);
    }

    public static boolean isPongFormat(Class<?> type)
    {
        return PongMessage.class.isAssignableFrom(type);
    }

    public static boolean isTextFormat(Class<?> type)
    {
        return DeploymentTypeUtils.getPrimitives().contains(type) || DeploymentTypeUtils.getPrimitiveClasses().contains(type)
                || Reader.class.isAssignableFrom(type);
    }

    private Class<?> returnType;
    private Encoder returnEncoder;
    // optional Session Parameter
    private ParamRef paramSession;
    // optional islast boolean used for partial message support
    private ParamRef paramIsLast;
    // mandatory format specifier
    private ParamRef paramFormat;
    private Map<String, ParamRef> pathParams;

    public JsrMessageCallableMethod(Class<?> pojo, Method method, Encoders encoders, Decoders decoders) throws InvalidSignatureException
    {
        super(pojo,method);

        setReturnType(method.getReturnType(),encoders);

        // walk through parameters
        Class<?> paramTypes[] = method.getParameterTypes();
        int len = paramTypes.length;
        for (int i = 0; i < len; i++)
        {
            Class<?> paramType = paramTypes[i];

            // Path Param
            String pathParam = getPathParam(paramType);
            if (StringUtil.isNotBlank(pathParam))
            {
                // TODO: limit to allowable path param types
                ParamRef paramRef = new ParamRef(i,paramType);
                Decoder decoder = decoders.getDecoder(paramType);
                if (!(decoder instanceof Decoder.Text))
                {
                    throw new WebSocketException("Unable to convert to PathParam with type: " + paramType);
                }
                paramRef.decoder = decoder;
                if (pathParams.containsKey(pathParam))
                {
                    throw new InvalidSignatureException("WebSocketPathParam of value [" + pathParam + "] is defined more than once");
                }
                pathParams.put(pathParam,paramRef);
                continue; // next param
            }

            // Session param
            if (Session.class.isAssignableFrom(paramType))
            {
                if (paramSession != null)
                {
                    throw new InvalidSignatureException("Session cannot appear multiple times as a parameter");
                }
                paramSession = new ParamRef(i,paramType);
                continue; // next param
            }

            // IsLast Boolean (for partial message support)
            if (Boolean.class.isAssignableFrom(paramType))
            {
                if (paramIsLast != null)
                {
                    throw new InvalidSignatureException("Boolean isLast cannot appear multiple times as a parameter");
                }
                paramIsLast = new ParamRef(i,paramType);
                continue; // next param
            }

            // Pong Format
            if (isPongFormat(paramType))
            {
                if (paramFormat != null)
                {
                    throw new InvalidSignatureException("Multiple Message Formats cannot appear as a separate parameters");
                }
                paramFormat = new ParamRef(i,paramType);
                continue; // next param
            }

            // Text or Binary
            Decoder decoder = decoders.getDecoder(paramType);
            if (decoder != null)
            {
                if (paramFormat != null)
                {
                    throw new InvalidSignatureException("Multiple Message Formats cannot appear as a separate parameters");
                }
                paramFormat = new ParamRef(i,paramType);
                paramFormat.decoder = decoder;
                continue; // next param
            }

            throw new InvalidSignatureException("Unknown parameter type: " + paramType);
        }
    }

    public String getPathParam(Class<?> paramType)
    {
        // override to implement on server side
        return null;
    }

    public boolean isBinaryFormat()
    {
        if (paramFormat == null)
        {
            return false;
        }
        if (paramFormat.decoder == null)
        {
            return false;
        }
        return (paramFormat.decoder instanceof Decoder.Binary) || (paramFormat.decoder instanceof Decoder.BinaryStream);
    }

    public boolean isPongFormat()
    {
        if (paramFormat == null)
        {
            return false;
        }
        return PongMessage.class.isAssignableFrom(paramFormat.type);
    }

    public boolean isTextFormat()
    {
        if (paramFormat == null)
        {
            return false;
        }
        if (paramFormat.decoder == null)
        {
            return false;
        }
        return (paramFormat.decoder instanceof Decoder.Text) || (paramFormat.decoder instanceof Decoder.TextStream);
    }

    public void setReturnType(Class<?> returnType, Encoders encoders)
    {
        this.returnType = returnType;
        if (returnType.equals(Void.TYPE))
        {
            // can't have encoder for Void
            return;
        }
        this.returnEncoder = encoders.getEncoder(returnType);

        if (this.returnEncoder == null)
        {
            throw new InvalidSignatureException("Unable to find encoder for return type: " + returnType);
        }
    }
}
