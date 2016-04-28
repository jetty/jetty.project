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

package org.eclipse.jetty.websocket.jsr356;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Predicate;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.DynamicArgs;
import org.eclipse.jetty.websocket.common.util.DynamicArgs.Arg;
import org.eclipse.jetty.websocket.common.util.UnorderedSignature;

/**
 * Used to identify Endpoint Functions
 */
public class EndpointFunctions
{
    private static final Logger LOG = Log.getLogger(EndpointFunctions.class);

    public enum Role
    {
        OPEN,
        CLOSE,
        ERROR,
        TEXT,
        TEXT_STREAM,
        BINARY,
        BINARY_STREAM,
        PONG
    }

    public class ArgRole
    {
        public Role role;
        public DynamicArgs.Builder argBuilder;
        public Predicate<Method> predicate;
    }

    private final Map<String, String> uriParams;
    private final ArgRole[] argRoles;

    public EndpointFunctions()
    {
        this(null);
    }

    public EndpointFunctions(Map<String, String> uriParams)
    {
        this.uriParams = uriParams;

        argRoles = new ArgRole[Role.values().length + 1];
        int argRoleIdx = 0;

        // ------------------------------------------------
        // @OnOpen
        ArgRole onOpen = new ArgRole();
        onOpen.role = Role.OPEN;
        onOpen.argBuilder = newDynamicBuilder(
                new Arg(Session.class)
        );
        onOpen.predicate = (method) ->
                hasAnnotation(method, OnOpen.class) &&
                        isPublicNonStatic(method) &&
                        isReturnType(method, Void.TYPE) &&
                        onOpen.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onOpen;

        // ------------------------------------------------
        // @OnClose
        ArgRole onClose = new ArgRole();
        onClose.role = Role.CLOSE;
        onClose.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(CloseReason.class) // close
        );
        onClose.predicate = (method) ->
                hasAnnotation(method, OnClose.class) &&
                        isPublicNonStatic(method) &&
                        isReturnType(method, Void.TYPE) &&
                        onClose.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onClose;

        // ------------------------------------------------
        // @OnError
        ArgRole onError = new ArgRole();
        onError.role = Role.ERROR;
        onError.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(Throwable.class) // cause
        );
        onError.predicate = (method) ->
                hasAnnotation(method, OnError.class) &&
                        isPublicNonStatic(method) &&
                        isReturnType(method, Void.TYPE) &&
                        onError.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onError;

        // ------------------------------------------------
        // @OnMessage / Text (whole message)
        ArgRole onText = new ArgRole();
        onText.role = Role.TEXT;
        onText.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(String.class) // message
        );
        onText.predicate = (method) ->
                hasAnnotation(method, OnMessage.class) &&
                        isPublicNonStatic(method) &&
                        hasSupportedReturnType(method) &&
                        onText.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onText;

        // ------------------------------------------------
        // @OnMessage / Binary (whole message, byte array)
        ArgRole onBinaryArray = new ArgRole();
        onBinaryArray.role = Role.BINARY;
        onBinaryArray.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(byte[].class), // buffer
                new Arg(int.class), // length
                new Arg(int.class) // offset
        );
        onBinaryArray.predicate = (method) ->
                hasAnnotation(method, OnMessage.class) &&
                        isPublicNonStatic(method) &&
                        hasSupportedReturnType(method) &&
                        onBinaryArray.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onBinaryArray;

        // ------------------------------------------------
        // @OnMessage / Binary (whole message, ByteBuffer)
        ArgRole onBinaryBuffer = new ArgRole();
        onBinaryBuffer.role = Role.BINARY;
        onBinaryBuffer.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(ByteBuffer.class) // buffer
        );
        onBinaryBuffer.predicate = (method) ->
                hasAnnotation(method, OnMessage.class) &&
                        isPublicNonStatic(method) &&
                        hasSupportedReturnType(method) &&
                        onBinaryBuffer.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onBinaryBuffer;

        // ------------------------------------------------
        // @OnMessage / Text (streamed)
        ArgRole onTextStream = new ArgRole();
        onTextStream.role = Role.TEXT_STREAM;
        onTextStream.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(Reader.class) // stream
        );
        onTextStream.predicate = (method) ->
                hasAnnotation(method, OnMessage.class) &&
                        isPublicNonStatic(method) &&
                        hasSupportedReturnType(method) &&
                        onTextStream.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onTextStream;

        // ------------------------------------------------
        // @OnMessage / Binary (streamed)
        ArgRole onBinaryStream = new ArgRole();
        onBinaryStream.role = Role.BINARY_STREAM;
        onBinaryStream.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(InputStream.class) // stream
        );
        onBinaryStream.predicate = (method) ->
                hasAnnotation(method, OnMessage.class) &&
                        isPublicNonStatic(method) &&
                        hasSupportedReturnType(method) &&
                        onBinaryStream.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onBinaryStream;

        // ------------------------------------------------
        // @OnMessage / Pong
        ArgRole onPong = new ArgRole();
        onPong.role = Role.PONG;
        onPong.argBuilder = newDynamicBuilder(
                new Arg(Session.class),
                new Arg(PongMessage.class) // payload
        );
        onPong.predicate = (method) ->
                hasAnnotation(method, OnMessage.class) &&
                        isPublicNonStatic(method) &&
                        isReturnType(method, Void.TYPE) &&
                        onPong.argBuilder.hasMatchingSignature(method);
        argRoles[argRoleIdx++] = onPong;
    }

    private DynamicArgs.Builder newDynamicBuilder(Arg... args)
    {
        DynamicArgs.Builder argBuilder = new DynamicArgs.Builder();
        int argCount = args.length;
        if (this.uriParams != null)
            argCount += uriParams.size();

        Arg[] callArgs = new Arg[argCount];
        int idx = 0;
        for (Arg arg : args)
        {
            callArgs[idx++] = arg;
        }

        if (this.uriParams != null)
        {
            for (Map.Entry<String, String> uriParam : uriParams.entrySet())
            {
                // TODO: translate from UriParam String to method param type?
                // TODO: use decoder?
                callArgs[idx++] = new Arg(uriParam.getValue().getClass()).setTag(uriParam.getKey());
            }
        }

        argBuilder.addSignature(new UnorderedSignature(callArgs));

        return argBuilder;
    }

    private static boolean hasAnnotation(Method method, Class<? extends Annotation> annoClass)
    {
        return (method.getAnnotation(annoClass) != null);
    }

    private static boolean isReturnType(Method method, Class<?> type)
    {
        if (!type.equals(method.getReturnType()))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(System.lineSeparator());
            err.append("Return type must be ").append(type);
            LOG.warn(err.toString());
            return false;
        }
        return true;
    }

    private static boolean hasSupportedReturnType(Method method)
    {
        // TODO: check Encoder list
        return true;
    }

    public static boolean isPublicNonStatic(Method method)
    {
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(System.lineSeparator());
            err.append("Method modifier must be public");
            LOG.warn(err.toString());
            return false;
        }

        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(System.lineSeparator());
            err.append("Method modifier must not be static");
            LOG.warn(err.toString());
            return false;
        }
        return true;
    }

    public ArgRole getArgRole(Method method, Class<? extends Annotation> annoClass, Role role)
    {
        ArgRole ret = null;

        for (ArgRole argRole : this.argRoles)
        {
            if ((argRole.role == role) && (argRole.predicate.test(method)))
            {
                ret = argRole;
            }
        }

        if (ret == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of @");
            err.append(annoClass.getSimpleName());
            err.append(" method ");
            err.append(method);
            throw new InvalidSignatureException(err.toString());
        }

        return ret;
    }

    public ArgRole findArgRole(Method method)
    {
        for (ArgRole argRole : this.argRoles)
        {
            if (argRole.predicate.test(method))
            {
                return argRole;
            }
        }

        return null;
    }
}
