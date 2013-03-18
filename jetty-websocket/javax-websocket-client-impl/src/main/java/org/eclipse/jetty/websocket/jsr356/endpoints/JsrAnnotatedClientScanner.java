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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.events.ParamList;
import org.eclipse.jetty.websocket.common.events.annotated.AbstractMethodAnnotationScanner;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.decoders.Decoders;
import org.eclipse.jetty.websocket.jsr356.encoders.Encoders;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrMethodParameters.Param;

/**
 * Scanner for javax.websocket {@link WebSocketEndpoint &#064;WebSocketEndpoint} and {@link ClientEndpoint &#064;ClientEndpoint} annotated websockets
 */
public class JsrAnnotatedClientScanner extends AbstractMethodAnnotationScanner<JsrAnnotatedMetadata>
{
    private static final Logger LOG = Log.getLogger(JsrAnnotatedClientScanner.class);

    private static final ParamList validOpenParams;
    private static final ParamList validCloseParams;
    private static final ParamList validErrorParams;

    static
    {
        validOpenParams = new ParamList();
        validOpenParams.addParams(Session.class);
        validOpenParams.addParams(EndpointConfig.class);

        validCloseParams = new ParamList();
        validCloseParams.addParams(Session.class);
        validCloseParams.addParams(CloseReason.class);

        validErrorParams = new ParamList();
        validErrorParams.addParams(Session.class);
        validErrorParams.addParams(Throwable.class);

    }

    protected final Class<?> pojo;
    protected final Encoders encoders;
    protected final Decoders decoders;

    public JsrAnnotatedClientScanner(Class<?> websocket)
    {
        this.pojo = websocket;

        ClientEndpoint anno = websocket.getAnnotation(ClientEndpoint.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException("Unsupported WebSocket object, missing @" + ClientEndpoint.class + " annotation");
        }

        this.encoders = new Encoders(anno.encoders());
        this.decoders = new Decoders(anno.decoders());
    }

    private void assertValidJsrSignature(Method method, Class<? extends Annotation> annoClass, ParamList validParams)
    {
        JsrMethodParameters params = new JsrMethodParameters(method);

        // First, find the path-mapping parameters
        for (Param param : params)
        {
            String varname = getPathMappingParameterVariable(param.type);
            if (varname != null)
            {
                param.setPathParamVariable(varname);
            }
        }

        // Next find the valid parameter sets and flag them
        for (Class<?>[] paramSet : validParams)
        {
            // Each entry in validParams is a set of possible valid references.
            // If not all parts of the set are present, that set isn't valid for the provided parameters.

            if (params.containsParameterSet(paramSet))
            {
                // flag as valid
                params.setValid(paramSet);
            }
        }

        // Finally, ensure we identified all of the parameters
        for (Param param : params)
        {
            if (param.isValid() == false)
            {
                StringBuilder err = new StringBuilder();
                err.append("Encountered invalid/unhandled parameter <");
                err.append(param.type.getName());
                err.append("> (position ").append(param.index).append(") in method <");
                err.append(method.getName());
                err.append("> of object <");
                err.append(pojo.getName());
                err.append("> that doesn't fit the requirements for the @");
                err.append(annoClass.getSimpleName());
                err.append(" annotation");

                throw new InvalidSignatureException(err.toString());
            }
        }
    }

    public String getPathMappingParameterVariable(Class<?> type)
    {
        /* override to implement */
        return null;
    }

    @Override
    public void onMethodAnnotation(JsrAnnotatedMetadata metadata, Class<?> pojo, Method method, Annotation annotation)
    {
        LOG.debug("onMethodAnnotation({}, {}, {}, {})",metadata,pojo,method,annotation);

        if (isAnnotation(annotation,OnOpen.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertValidJsrSignature(method,OnOpen.class,validOpenParams);
            assertUnset(metadata.onOpen,OnOpen.class,method);
            metadata.onOpen = new CallableMethod(pojo,method);
            return;
        }

        if (isAnnotation(annotation,OnClose.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertValidJsrSignature(method,OnClose.class,validCloseParams);
            assertUnset(metadata.onClose,OnClose.class,method);
            metadata.onClose = new CallableMethod(pojo,method);
            return;
        }

        if (isAnnotation(annotation,OnError.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertValidJsrSignature(method,OnError.class,validErrorParams);
            assertUnset(metadata.onError,OnError.class,method);
            metadata.onError = new CallableMethod(pojo,method);
            return;
        }

        if (isAnnotation(annotation,OnMessage.class))
        {
            assertIsPublicNonStatic(method);

            JsrMessageCallableMethod callable = new JsrMessageCallableMethod(pojo,method,encoders,decoders);
            if (callable.isTextFormat())
            {
                // TEXT
                assertUnset(metadata.onText,OnMessage.class,method);
                metadata.onText = callable;
                return;
            }

            if (callable.isBinaryFormat())
            {
                // BINARY
                assertUnset(metadata.onBinary,OnMessage.class,method);
                metadata.onBinary = callable;
                return;
            }

            if (callable.isPongFormat())
            {
                // PONG
                assertUnset(metadata.onPong,OnMessage.class,method);
                metadata.onPong = callable;
                return;
            }

            return;
        }
    }

    public JsrAnnotatedMetadata scan()
    {
        JsrAnnotatedMetadata metadata = new JsrAnnotatedMetadata();
        scanMethodAnnotations(metadata,pojo);
        return metadata;
    }
}
