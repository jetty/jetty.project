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
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfiguration;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketClient;
import javax.websocket.WebSocketClose;
import javax.websocket.WebSocketError;
import javax.websocket.WebSocketMessage;
import javax.websocket.WebSocketOpen;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.events.ParamList;
import org.eclipse.jetty.websocket.common.events.annotated.AbstractMethodAnnotationScanner;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrMethodParameters.Param;

/**
 * Scanner for javax.websocket {@link WebSocketEndpoint &#064;WebSocketEndpoint} and {@link WebSocketClient &#064;WebSocketClient} annotated websockets
 */
public class JsrAnnotatedClientScanner extends AbstractMethodAnnotationScanner<JsrAnnotatedMetadata>
{
    private static final Logger LOG = Log.getLogger(JsrAnnotatedClientScanner.class);

    private static final ParamList validOpenParams;
    private static final ParamList validCloseParams;
    private static final ParamList validErrorParams;
    private static final ParamList validPongFormatParams;
    private static final ParamList validTextFormatParams;
    private static final ParamList validBinaryFormatParams;

    static
    {
        validOpenParams = new ParamList();
        validOpenParams.addParams(Session.class);
        validOpenParams.addParams(EndpointConfiguration.class);

        validCloseParams = new ParamList();
        validCloseParams.addParams(Session.class);
        validCloseParams.addParams(CloseReason.class);

        validErrorParams = new ParamList();
        validErrorParams.addParams(Session.class);
        validErrorParams.addParams(Throwable.class);

        // TEXT Formats
        validTextFormatParams = new ParamList();
        // partial message
        validTextFormatParams.addParams(String.class,Boolean.TYPE);
        validTextFormatParams.addParams(String.class,Boolean.class);
        // whole message
        validTextFormatParams.addParams(String.class);
        // java primitives
        validTextFormatParams.addParams(Boolean.TYPE);
        validTextFormatParams.addParams(Byte.TYPE);
        validTextFormatParams.addParams(Character.TYPE);
        validTextFormatParams.addParams(Double.TYPE);
        validTextFormatParams.addParams(Float.TYPE);
        validTextFormatParams.addParams(Integer.TYPE);
        validTextFormatParams.addParams(Long.TYPE);
        validTextFormatParams.addParams(Short.TYPE);
        // java primitives class equivalents
        validTextFormatParams.addParams(Boolean.class);
        validTextFormatParams.addParams(Byte.class);
        validTextFormatParams.addParams(Character.class);
        validTextFormatParams.addParams(Double.class);
        validTextFormatParams.addParams(Float.class);
        validTextFormatParams.addParams(Integer.class);
        validTextFormatParams.addParams(Long.class);
        validTextFormatParams.addParams(Short.class);
        // streaming
        validTextFormatParams.addParams(Reader.class);

        // BINARY Formats
        validBinaryFormatParams = new ParamList();
        // partial message
        validBinaryFormatParams.addParams(ByteBuffer.class,Boolean.TYPE);
        validBinaryFormatParams.addParams(ByteBuffer.class,Boolean.class);
        validBinaryFormatParams.addParams(byte[].class,Boolean.TYPE);
        validBinaryFormatParams.addParams(byte[].class,Boolean.class);
        // whole message
        validBinaryFormatParams.addParams(ByteBuffer.class);
        validBinaryFormatParams.addParams(byte[].class);
        // streaming
        validBinaryFormatParams.addParams(InputStream.class);

        // PONG Format
        validPongFormatParams = new ParamList();
        validPongFormatParams.addParams(PongMessage.class);
    }

    protected final Class<?> pojo;
    protected final Class<? extends Encoder> encoders[];
    protected final Class<? extends Decoder> decoders[];
    protected final ParamList validTextDecoderParameters;
    protected final ParamList validBinaryDecoderParameters;

    public JsrAnnotatedClientScanner(Class<?> websocket)
    {
        this.pojo = websocket;

        WebSocketClient anno = websocket.getAnnotation(WebSocketClient.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException("Unsupported WebSocket object, missing @" + WebSocketClient.class + " annotation");
        }

        this.encoders = anno.encoders();
        this.decoders = anno.decoders();

        this.validTextDecoderParameters = new ParamList();
        this.validBinaryDecoderParameters = new ParamList();

        // decoder based valid parameters
        for (Class<? extends Decoder> decoder : this.decoders)
        {
            if (Decoder.Text.class.isAssignableFrom(decoder) || Decoder.TextStream.class.isAssignableFrom(decoder))
            {
                // Text decoder
                decoder.getTypeParameters();
                // TODO: Fixme
            }

            if (Decoder.Binary.class.isAssignableFrom(decoder) || Decoder.BinaryStream.class.isAssignableFrom(decoder))
            {
                // Binary decoder
                // TODO: Fixme
            }
        }
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

        if (isAnnotation(annotation,WebSocketOpen.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertValidJsrSignature(method,WebSocketOpen.class,validOpenParams);
            assertUnset(metadata.onOpen,WebSocketOpen.class,method);
            metadata.onOpen = new CallableMethod(pojo,method);
            return;
        }

        if (isAnnotation(annotation,WebSocketClose.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertValidJsrSignature(method,WebSocketClose.class,validCloseParams);
            assertUnset(metadata.onClose,WebSocketClose.class,method);
            metadata.onClose = new CallableMethod(pojo,method);
            return;
        }

        if (isAnnotation(annotation,WebSocketError.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertValidJsrSignature(method,WebSocketError.class,validErrorParams);
            assertUnset(metadata.onError,WebSocketError.class,method);
            metadata.onError = new CallableMethod(pojo,method);
            return;
        }

        if (isAnnotation(annotation,WebSocketMessage.class))
        {
            assertIsPublicNonStatic(method);
            /*
            JsrMessageCallableMethod callable = new JsrMessageCallableMethod(pojo,method);
            callable.setReturnType(method.getReturnType(),encoders);

            JsrMethodParameters params = new JsrMethodParameters(method);

            boolean foundSession = false;
            boolean foundIsLast = false;
            boolean foundFormat = false;
            // Find the path-mapping and Session parameters
            for (Param param : params)
            {
                // (optional) Path Mapping Parameters
                String varname = getPathMappingParameterVariable(param.type);
                if (varname != null)
                {
                    param.setPathParamVariable(varname);
                }

                // (optional) Session parameter
                if (Session.class.isAssignableFrom(param.type))
                {
                    if(foundSession) {
                        throw new InvalidSignatureException("Duplicate "+Session.class+" parameter found in " + );
                    }
                    param.setValid(true);
                }

                // (optional) isLast parameter
                if (Boolean.class.isAssignableFrom(param.type))
                {
                    param.setValid(true);
                }
            }

            // Ensure we identified all of the parameters
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
                    err.append(WebSocketMessage.class.getSimpleName());
                    err.append(" annotation");

                    throw new InvalidSignatureException(err.toString());
                }
            }

            // Find the Message Format Parameters
            Class<?> formatParams[] = null;
            if ((formatParams = params.containsAny(validTextFormatParams)) != null)
            {
                // TEXT
                params.setValid(formatParams);
                metadata.onText = callable;
                assertUnset(metadata.onText,WebSocketMessage.class,method);
            }

            if ((formatParams = params.containsAny(validBinaryFormatParams)) != null)
            {
                // BINARY
                params.setValid(formatParams);
                metadata.onBinary = callable;
                assertUnset(metadata.onBinary,WebSocketMessage.class,method);
            }

            if ((formatParams = params.containsAny(validPongFormatParams)) != null)
            {
                // PONG
                params.setValid(formatParams);
                metadata.onPong = callable;
                assertUnset(metadata.onPong,WebSocketMessage.class,method);
            }

            */
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
