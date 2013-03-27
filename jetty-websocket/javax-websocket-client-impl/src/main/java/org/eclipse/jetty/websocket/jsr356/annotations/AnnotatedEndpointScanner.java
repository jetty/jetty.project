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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.AbstractMethodAnnotationScanner;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.utils.MethodUtils;

public class AnnotatedEndpointScanner extends AbstractMethodAnnotationScanner<JsrMetadata<?>>
{
    private static final Logger LOG = Log.getLogger(AnnotatedEndpointScanner.class);

    private final LinkedList<IJsrParamId> paramsOnOpen;
    private final LinkedList<IJsrParamId> paramsOnClose;
    private final LinkedList<IJsrParamId> paramsOnError;
    private final LinkedList<IJsrParamId> paramsOnMessage;
    private final JsrMetadata<?> metadata;

    public AnnotatedEndpointScanner(JsrMetadata<?> metadata)
    {
        this.metadata = metadata;

        paramsOnOpen = new LinkedList<>();
        paramsOnClose = new LinkedList<>();
        paramsOnError = new LinkedList<>();
        paramsOnMessage = new LinkedList<>();

        paramsOnOpen.add(JsrParamIdOnOpen.INSTANCE);
        metadata.customizeParamsOnOpen(paramsOnOpen);
        paramsOnClose.add(JsrParamIdOnClose.INSTANCE);
        metadata.customizeParamsOnClose(paramsOnClose);
        paramsOnError.add(JsrParamIdOnError.INSTANCE);
        metadata.customizeParamsOnError(paramsOnError);

        paramsOnMessage.add(JsrParamIdBinary.INSTANCE);
        paramsOnMessage.add(JsrParamIdText.INSTANCE);
        paramsOnMessage.add(JsrParamIdPong.INSTANCE);
        metadata.customizeParamsOnMessage(paramsOnMessage);
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
            err.append(MethodUtils.toString(pojo,callable.getMethod()));
            err.append(" and ");
            err.append(MethodUtils.toString(pojo,method));

            throw new InvalidSignatureException(err.toString());
        }
    }

    @Override
    public void onMethodAnnotation(JsrMetadata<?> metadata, Class<?> pojo, Method method, Annotation annotation)
    {
        LOG.debug("onMethodAnnotation({}, {}, {}, {})",metadata,pojo,method,annotation);

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
            assertIsReturn(method,Void.TYPE);
            // ParameterizedMethod msgMethod = establishCallable(metadata,pojo,method,paramsOnMessage,OnMessage.class);
            // switch (msgMethod.getMessageType())
            // {
            // case TEXT:
            // metadata.onText = msgMethod;
            // break;
            // case BINARY:
            // metadata.onBinary = msgMethod;
            // break;
            // case PONG:
            // metadata.onPong = msgMethod;
            // break;
            // default:
            // StringBuilder err = new StringBuilder();
            // err.append("Invalid @OnMessage method signature,");
            // err.append(" Missing type TEXT, BINARY, or PONG parameter: ");
            // err.append(msgMethod.getFullyQualifiedMethodName());
            // throw new InvalidSignatureException(err.toString());
            // }
        }
    }

    public JsrMetadata<?> scan()
    {
        scanMethodAnnotations(metadata,metadata.pojo);
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
                err.append(MethodUtils.toString(pojo,method));

                throw new InvalidSignatureException(err.toString());
            }
        }
    }

    private boolean visitParam(JsrCallable callable, Param param, List<IJsrParamId> paramIds)
    {
        for (IJsrParamId paramId : paramIds)
        {
            if (paramId.process(param,callable))
            {
                // Successfully identified
                return true;
            }
        }

        // Failed identification as a known parameter
        return false;
    }
}
