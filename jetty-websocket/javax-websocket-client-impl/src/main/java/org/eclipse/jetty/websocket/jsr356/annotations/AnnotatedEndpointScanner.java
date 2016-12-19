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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.AbstractMethodAnnotationScanner;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class AnnotatedEndpointScanner<T extends Annotation, C extends EndpointConfig> extends AbstractMethodAnnotationScanner<AnnotatedEndpointMetadata<T, C>>
{
    private static final Logger LOG = Log.getLogger(AnnotatedEndpointScanner.class);

    private final LinkedList<IJsrParamId> paramsOnOpen;
    private final LinkedList<IJsrParamId> paramsOnClose;
    private final LinkedList<IJsrParamId> paramsOnError;
    private final LinkedList<IJsrParamId> paramsOnMessage;
    private final AnnotatedEndpointMetadata<T, C> metadata;

    public AnnotatedEndpointScanner(AnnotatedEndpointMetadata<T, C> metadata)
    {
        this.metadata = metadata;

        paramsOnOpen = new LinkedList<>();
        paramsOnClose = new LinkedList<>();
        paramsOnError = new LinkedList<>();
        paramsOnMessage = new LinkedList<>();

        metadata.customizeParamsOnOpen(paramsOnOpen);
        paramsOnOpen.add(JsrParamIdOnOpen.INSTANCE);

        metadata.customizeParamsOnClose(paramsOnClose);
        paramsOnClose.add(JsrParamIdOnClose.INSTANCE);

        metadata.customizeParamsOnError(paramsOnError);
        paramsOnError.add(JsrParamIdOnError.INSTANCE);

        metadata.customizeParamsOnMessage(paramsOnMessage);
        paramsOnMessage.add(JsrParamIdText.INSTANCE);
        paramsOnMessage.add(JsrParamIdBinary.INSTANCE);
        paramsOnMessage.add(JsrParamIdPong.INSTANCE);
    }

    private void assertNotDuplicate(JsrCallable callable, Class<? extends Annotation> methodAnnotationClass, Class<?> pojo, Method method)
    {
        if (callable != null)
        {
            // Duplicate annotation detected
            StringBuilder err = new StringBuilder();
            err.append("Encountered duplicate method annotations @");
            err.append(methodAnnotationClass.getSimpleName());
            err.append(" on ");
            err.append(ReflectUtils.toString(pojo,callable.getMethod()));
            err.append(" and ");
            err.append(ReflectUtils.toString(pojo,method));

            throw new InvalidSignatureException(err.toString());
        }
    }

    @Override
    public void onMethodAnnotation(AnnotatedEndpointMetadata<T, C> metadata, Class<?> pojo, Method method, Annotation annotation)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onMethodAnnotation({}, {}, {}, {})",metadata,pojo,method,annotation);
        }

        if (isAnnotation(annotation,OnOpen.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertNotDuplicate(metadata.onOpen,OnOpen.class,pojo,method);
            OnOpenCallable onopen = new OnOpenCallable(pojo,method);
            visitMethod(onopen,pojo,method,paramsOnOpen,OnOpen.class);
            metadata.onOpen = onopen;
            return;
        }

        if (isAnnotation(annotation,OnClose.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertNotDuplicate(metadata.onClose,OnClose.class,pojo,method);
            OnCloseCallable onclose = new OnCloseCallable(pojo,method);
            visitMethod(onclose,pojo,method,paramsOnClose,OnClose.class);
            metadata.onClose = onclose;
            return;
        }

        if (isAnnotation(annotation,OnError.class))
        {
            assertIsPublicNonStatic(method);
            assertIsReturn(method,Void.TYPE);
            assertNotDuplicate(metadata.onError,OnError.class,pojo,method);
            OnErrorCallable onerror = new OnErrorCallable(pojo,method);
            visitMethod(onerror,pojo,method,paramsOnError,OnError.class);
            metadata.onError = onerror;
            return;
        }

        if (isAnnotation(annotation,OnMessage.class))
        {
            assertIsPublicNonStatic(method);
            // assertIsReturn(method,Void.TYPE); // no validation, it can be any return type
            OnMessageCallable onmessage = new OnMessageCallable(pojo,method);
            visitMethod(onmessage,pojo,method,paramsOnMessage,OnMessage.class);

            OnMessage messageAnno = (OnMessage) annotation;
            Param param = onmessage.getMessageObjectParam();
            switch (param.role)
            {
                case MESSAGE_BINARY:
                    metadata.onBinary = new OnMessageBinaryCallable(onmessage);
                    metadata.setMaxBinaryMessageSize(messageAnno.maxMessageSize());
                    break;
                case MESSAGE_BINARY_STREAM:
                    metadata.onBinaryStream = new OnMessageBinaryStreamCallable(onmessage);
                    metadata.setMaxBinaryMessageSize(messageAnno.maxMessageSize());
                    break;
                case MESSAGE_TEXT:
                    metadata.onText = new OnMessageTextCallable(onmessage);
                    metadata.setMaxTextMessageSize(messageAnno.maxMessageSize());
                    break;
                case MESSAGE_TEXT_STREAM:
                    metadata.onTextStream = new OnMessageTextStreamCallable(onmessage);
                    metadata.setMaxTextMessageSize(messageAnno.maxMessageSize());
                    break;
                case MESSAGE_PONG:
                    metadata.onPong = new OnMessagePongCallable(onmessage);
                    break;
                default:
                    StringBuilder err = new StringBuilder();
                    err.append("An unrecognized message type <");
                    err.append(param.type);
                    err.append(">: does not meet specified type categories of [TEXT, BINARY, DECODER, or PONG]");
                    throw new InvalidSignatureException(err.toString());
            }
        }
    }

    public AnnotatedEndpointMetadata<T, C> scan()
    {
        scanMethodAnnotations(metadata,metadata.getEndpointClass());
        return metadata;
    }

    private void visitMethod(JsrCallable callable, Class<?> pojo, Method method, LinkedList<IJsrParamId> paramIds,
            Class<? extends Annotation> methodAnnotationClass)
    {
        // Identify all of the parameters
        for (Param param : callable.getParams())
        {
            if (!visitParam(callable,param,paramIds))
            {
                StringBuilder err = new StringBuilder();
                err.append("Encountered unknown parameter type <");
                err.append(param.type.getName());
                err.append("> on @");
                err.append(methodAnnotationClass.getSimpleName());
                err.append(" annotated method: ");
                err.append(ReflectUtils.toString(pojo,method));

                throw new InvalidSignatureException(err.toString());
            }
        }
    }

    private boolean visitParam(JsrCallable callable, Param param, List<IJsrParamId> paramIds)
    {
        for (IJsrParamId paramId : paramIds)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.process()",paramId);
            }
            if (paramId.process(param,callable))
            {
                // Successfully identified
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Identified: {}",param);
                }
                return true;
            }
        }

        // Failed identification as a known parameter
        return false;
    }
}
