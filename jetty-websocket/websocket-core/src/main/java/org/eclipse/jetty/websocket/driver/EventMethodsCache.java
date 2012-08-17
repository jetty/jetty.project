//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.driver;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.protocol.Frame;

public class EventMethodsCache
{
    @SuppressWarnings("serial")
    public static class InvalidSignatureException extends InvalidWebSocketException
    {
        public static InvalidSignatureException build(Method method, Class<? extends Annotation> annoClass, ParamList... paramlists)
        {
            // Build big detailed exception to help the developer
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(StringUtil.__LINE_SEPARATOR);

            err.append("Acceptable method declarations for @");
            err.append(annoClass.getSimpleName());
            err.append(" are:");
            for (ParamList validParams : paramlists)
            {
                for (Class<?>[] params : validParams)
                {
                    err.append(StringUtil.__LINE_SEPARATOR);
                    err.append("public void ").append(method.getName());
                    err.append('(');
                    boolean delim = false;
                    for (Class<?> type : params)
                    {
                        if (delim)
                        {
                            err.append(',');
                        }
                        err.append(' ');
                        err.append(type.getName());
                        if (type.isArray())
                        {
                            err.append("[]");
                        }
                        delim = true;
                    }
                    err.append(')');
                }
            }
            return new InvalidSignatureException(err.toString());
        }

        public InvalidSignatureException(String message)
        {
            super(message);
        }
    }

    @SuppressWarnings("serial")
    private static class ParamList extends ArrayList<Class<?>[]>
    {
        public void addParams(Class<?>... paramTypes)
        {
            this.add(paramTypes);
        }
    }
    /**
     * Parameter list for &#064;OnWebSocketMessage (Binary mode)
     */
    private static final ParamList validBinaryParams;
    /**
     * Parameter list for &#064;OnWebSocketConnect
     */
    private static final ParamList validConnectParams;
    /**
     * Parameter list for &#064;OnWebSocketClose
     */
    private static final ParamList validCloseParams;
    /**
     * Parameter list for &#064;OnWebSocketFrame
     */
    private static final ParamList validFrameParams;

    /**
     * Parameter list for &#064;OnWebSocketMessage (Text mode)
     */
    private static final ParamList validTextParams;

    static
    {
        validConnectParams = new ParamList();
        validConnectParams.addParams(WebSocketConnection.class);

        validCloseParams = new ParamList();
        validCloseParams.addParams(int.class,String.class);
        validCloseParams.addParams(WebSocketConnection.class,int.class,String.class);

        validTextParams = new ParamList();
        validTextParams.addParams(String.class);
        validTextParams.addParams(WebSocketConnection.class,String.class);
        validTextParams.addParams(Reader.class);
        validTextParams.addParams(WebSocketConnection.class,Reader.class);

        validBinaryParams = new ParamList();
        validBinaryParams.addParams(byte[].class,int.class,int.class);
        validBinaryParams.addParams(WebSocketConnection.class,byte[].class,int.class,int.class);
        validBinaryParams.addParams(InputStream.class);
        validBinaryParams.addParams(WebSocketConnection.class,InputStream.class);

        validFrameParams = new ParamList();
        validFrameParams.addParams(Frame.class);
        validFrameParams.addParams(WebSocketConnection.class,Frame.class);
    }

    private ConcurrentHashMap<Class<?>, EventMethods> cache;

    public EventMethodsCache()
    {
        cache = new ConcurrentHashMap<>();
    }

    private void assertIsPublicNonStatic(Method method)
    {
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(StringUtil.__LINE_SEPARATOR);

            err.append("Method modifier must be public");

            throw new InvalidWebSocketException(err.toString());
        }

        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(StringUtil.__LINE_SEPARATOR);

            err.append("Method modifier may not be static");

            throw new InvalidWebSocketException(err.toString());
        }
    }

    private void assertIsReturn(Method method, Class<?> type)
    {
        if (!type.equals(method.getReturnType()))
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(StringUtil.__LINE_SEPARATOR);

            err.append("Return type must be ").append(type);

            throw new InvalidWebSocketException(err.toString());
        }
    }

    private void assertUnset(EventMethod event, Class<? extends Annotation> annoClass, Method method)
    {
        if (event != null)
        {
            // Attempt to add duplicate frame type (a no-no)
            StringBuilder err = new StringBuilder();
            err.append("Duplicate @").append(annoClass.getSimpleName()).append(" declaration on ");
            err.append(method);
            err.append(StringUtil.__LINE_SEPARATOR);

            err.append("@").append(annoClass.getSimpleName()).append(" previously declared at ");
            err.append(event.getMethod());

            throw new InvalidWebSocketException(err.toString());
        }
    }

    private void assertValidSignature(Method method, Class<? extends Annotation> annoClass, ParamList validParams)
    {
        assertIsPublicNonStatic(method);
        assertIsReturn(method,Void.TYPE);

        boolean valid = false;

        // validate parameters
        Class<?> actual[] = method.getParameterTypes();
        for (Class<?>[] params : validParams)
        {
            if (isSameParameters(actual,params))
            {
                valid = true;
                break;
            }
        }

        if (!valid)
        {
            throw InvalidSignatureException.build(method,annoClass,validParams);
        }
    }

    public int count()
    {
        return cache.size();
    }

    /**
     * Perform the basic discovery mechanism for WebSocket events from the provided pojo.
     * 
     * @param pojo
     *            the pojo to scan
     * @return the discovered event methods
     * @throws InvalidWebSocketException
     */
    private EventMethods discoverMethods(Class<?> pojo) throws InvalidWebSocketException
    {
        if (WebSocketListener.class.isAssignableFrom(pojo))
        {
            return scanListenerMethods(pojo);
        }

        WebSocket anno = pojo.getAnnotation(WebSocket.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException(pojo.getName() + " does not implement " + WebSocketListener.class.getName() + " or use the @"
                    + WebSocket.class.getName() + " annotation");
        }

        return scanAnnotatedMethods(pojo);
    }

    public EventMethods getMethods(Class<?> pojo) throws InvalidWebSocketException
    {
        if (pojo == null)
        {
            throw new InvalidWebSocketException("Cannot get methods for null class");
        }
        if (cache.containsKey(pojo))
        {
            return cache.get(pojo);
        }
        EventMethods methods = discoverMethods(pojo);
        cache.put(pojo,methods);
        return methods;
    }

    private boolean isSameParameters(Class<?>[] actual, Class<?>[] params)
    {
        if(actual.length != params.length) {
            // skip
            return false;
        }

        int len = params.length;
        for(int i=0; i<len; i++) {
            if(!actual[i].equals(params[i])) {
                return false; // not valid
            }
        }

        return true;
    }

    private boolean isSignatureMatch(Method method, ParamList validParams)
    {
        assertIsPublicNonStatic(method);
        assertIsReturn(method,Void.TYPE);

        // validate parameters
        Class<?> actual[] = method.getParameterTypes();
        for (Class<?>[] params : validParams)
        {
            if (isSameParameters(actual,params))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Register a pojo with the cache.
     * 
     * @param pojo
     *            the pojo to register with the cache.
     * @throws InvalidWebSocketException
     *             if the pojo does not conform to a WebSocket implementation.
     */
    public void register(Class<?> pojo) throws InvalidWebSocketException
    {
        getMethods(pojo);
    }

    private EventMethods scanAnnotatedMethods(Class<?> pojo)
    {
        Class<?> clazz = pojo;
        EventMethods events = new EventMethods(pojo,true);

        clazz = pojo;
        while (clazz.getAnnotation(WebSocket.class) != null)
        {
            for (Method method : clazz.getDeclaredMethods())
            {
                if (method.getAnnotation(OnWebSocketConnect.class) != null)
                {
                    assertValidSignature(method,OnWebSocketConnect.class,validConnectParams);
                    assertUnset(events.onConnect,OnWebSocketConnect.class,method);
                    events.onConnect = new EventMethod(pojo,method);
                    continue;
                }

                if (method.getAnnotation(OnWebSocketMessage.class) != null)
                {
                    if (isSignatureMatch(method,validTextParams))
                    {
                        // Text mode
                        // TODO

                        assertUnset(events.onText,OnWebSocketMessage.class,method);
                        events.onText = new EventMethod(pojo,method);
                        continue;
                    }

                    if (isSignatureMatch(method,validBinaryParams))
                    {
                        // Binary Mode
                        // TODO
                        assertUnset(events.onBinary,OnWebSocketMessage.class,method);
                        events.onBinary = new EventMethod(pojo,method);
                        continue;
                    }

                    throw InvalidSignatureException.build(method,OnWebSocketMessage.class,validTextParams,validBinaryParams);
                }

                if (method.getAnnotation(OnWebSocketClose.class) != null)
                {
                    assertValidSignature(method,OnWebSocketClose.class,validCloseParams);
                    assertUnset(events.onClose,OnWebSocketClose.class,method);
                    events.onClose = new EventMethod(pojo,method);
                    continue;
                }

                if (method.getAnnotation(OnWebSocketFrame.class) != null)
                {
                    assertValidSignature(method,OnWebSocketFrame.class,validFrameParams);
                    assertUnset(events.onFrame,OnWebSocketFrame.class,method);
                    events.onFrame = new EventMethod(pojo,method);
                    continue;
                }

                // Not a tagged method we are interested in, ignore
            }

            // try superclass now
            clazz = clazz.getSuperclass();
        }

        return events;
    }

    private EventMethods scanListenerMethods(Class<?> pojo)
    {
        EventMethods events = new EventMethods(pojo,false);

        // This is a WebSocketListener object
        events.onConnect = new EventMethod(pojo,"onWebSocketConnect",WebSocketConnection.class);
        events.onClose = new EventMethod(pojo,"onWebSocketClose",int.class,String.class);
        events.onBinary = new EventMethod(pojo,"onWebSocketBinary",byte[].class,int.class,int.class);
        events.onText = new EventMethod(pojo,"onWebSocketText",String.class);
        events.onException = new EventMethod(pojo,"onWebSocketException",WebSocketException.class);

        return events;
    }

    @Override
    public String toString()
    {
        return String.format("EventMethodsCache [cache.count=%d]",cache.size());
    }
}
