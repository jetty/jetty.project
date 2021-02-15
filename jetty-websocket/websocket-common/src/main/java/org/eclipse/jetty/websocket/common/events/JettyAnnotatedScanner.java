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

package org.eclipse.jetty.websocket.common.events;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.events.annotated.AbstractMethodAnnotationScanner;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.events.annotated.OptionalSessionCallableMethod;

public class JettyAnnotatedScanner extends AbstractMethodAnnotationScanner<JettyAnnotatedMetadata>
{
    private static final Logger LOG = Log.getLogger(JettyAnnotatedScanner.class);

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
     * Parameter list for &#064;OnWebSocketError
     */
    private static final ParamList validErrorParams;

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
        validConnectParams.addParams(Session.class);

        validCloseParams = new ParamList();
        validCloseParams.addParams(int.class, String.class);
        validCloseParams.addParams(Session.class, int.class, String.class);

        validErrorParams = new ParamList();
        validErrorParams.addParams(Throwable.class);
        validErrorParams.addParams(Session.class, Throwable.class);

        validTextParams = new ParamList();
        validTextParams.addParams(String.class);
        validTextParams.addParams(Session.class, String.class);
        validTextParams.addParams(Reader.class);
        validTextParams.addParams(Session.class, Reader.class);

        validBinaryParams = new ParamList();
        validBinaryParams.addParams(byte[].class, int.class, int.class);
        validBinaryParams.addParams(Session.class, byte[].class, int.class, int.class);
        validBinaryParams.addParams(InputStream.class);
        validBinaryParams.addParams(Session.class, InputStream.class);

        validFrameParams = new ParamList();
        validFrameParams.addParams(Frame.class);
        validFrameParams.addParams(Session.class, Frame.class);
    }

    @Override
    public void onMethodAnnotation(JettyAnnotatedMetadata metadata, Class<?> pojo, Method method, Annotation annotation)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onMethodAnnotation({}, {}, {}, {})", metadata, pojo, method, annotation);

        if (isAnnotation(annotation, OnWebSocketConnect.class))
        {
            assertValidSignature(method, OnWebSocketConnect.class, validConnectParams);
            assertUnset(metadata.onConnect, OnWebSocketConnect.class, method);
            metadata.onConnect = new CallableMethod(pojo, method);
            return;
        }

        if (isAnnotation(annotation, OnWebSocketMessage.class))
        {
            if (isSignatureMatch(method, validTextParams))
            {
                // Text mode
                assertUnset(metadata.onText, OnWebSocketMessage.class, method);
                metadata.onText = new OptionalSessionCallableMethod(pojo, method);
                return;
            }

            if (isSignatureMatch(method, validBinaryParams))
            {
                // Binary Mode
                // TODO
                assertUnset(metadata.onBinary, OnWebSocketMessage.class, method);
                metadata.onBinary = new OptionalSessionCallableMethod(pojo, method);
                return;
            }

            throw InvalidSignatureException.build(method, OnWebSocketMessage.class, validTextParams, validBinaryParams);
        }

        if (isAnnotation(annotation, OnWebSocketClose.class))
        {
            assertValidSignature(method, OnWebSocketClose.class, validCloseParams);
            assertUnset(metadata.onClose, OnWebSocketClose.class, method);
            metadata.onClose = new OptionalSessionCallableMethod(pojo, method);
            return;
        }

        if (isAnnotation(annotation, OnWebSocketError.class))
        {
            assertValidSignature(method, OnWebSocketError.class, validErrorParams);
            assertUnset(metadata.onError, OnWebSocketError.class, method);
            metadata.onError = new OptionalSessionCallableMethod(pojo, method);
            return;
        }

        if (isAnnotation(annotation, OnWebSocketFrame.class))
        {
            assertValidSignature(method, OnWebSocketFrame.class, validFrameParams);
            assertUnset(metadata.onFrame, OnWebSocketFrame.class, method);
            metadata.onFrame = new OptionalSessionCallableMethod(pojo, method);
            return;
        }
    }

    public JettyAnnotatedMetadata scan(Class<?> pojo)
    {
        JettyAnnotatedMetadata metadata = new JettyAnnotatedMetadata();
        scanMethodAnnotations(metadata, pojo);
        return metadata;
    }
}
