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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.AbstractMethodAnnotationScanner;

/**
 * Cache for discovered javax.websocket {@link WebSocketEndpoint &#064;WebSocketEndpoint} annotated websockets
 */
public class JavaxPojoAnnotationCache extends AbstractMethodAnnotationScanner<JavaxPojoMetadata>
{
    private static final Logger LOG = Log.getLogger(JavaxPojoAnnotationCache.class);
    public static final JavaxPojoAnnotationCache INSTANCE = new JavaxPojoAnnotationCache();

    public synchronized static JavaxPojoMetadata discover(Class<?> websocket)
    {
        // TODO: move to server side deployer
        // WebSocketEndpoint anno = websocket.getAnnotation(WebSocketEndpoint.class);
        // if (anno == null)
        // {
        // return null;
        // }

        JavaxPojoMetadata metadata = INSTANCE.cache.get(websocket);
        if (metadata == null)
        {
            metadata = new JavaxPojoMetadata();
            INSTANCE.scanMethodAnnotations(metadata,websocket);
            INSTANCE.cache.put(websocket,metadata);
        }

        return metadata;
    }

    public static JavaxPojoMetadata discover(Object websocket)
    {
        return discover(websocket.getClass());
    }

    private ConcurrentHashMap<Class<?>, JavaxPojoMetadata> cache;

    public JavaxPojoAnnotationCache()
    {
        cache = new ConcurrentHashMap<>();
    }

    @Override
    public void onMethodAnnotation(JavaxPojoMetadata metadata, Class<?> pojo, Method method, Annotation annotation)
    {
        LOG.debug("onMethodAnnotation({}, {}, {}, {})",metadata,pojo,method,annotation);
        // TODO Auto-generated method stub
    }
}
