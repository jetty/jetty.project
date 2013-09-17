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

package org.eclipse.jetty.websocket.jsr356.server.deploy;

import java.util.List;

import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.annotations.AbstractDiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Processing for &#64;{@link ServerEndpoint} annotations during Web App Annotation Scanning
 */
public class ServerEndpointAnnotationHandler extends AbstractDiscoverableAnnotationHandler
{
    private static final String ANNOTATION_NAME = "javax.websocket.server.ServerEndpoint";
    private static final Logger LOG = Log.getLogger(ServerEndpointAnnotationHandler.class);

    public ServerEndpointAnnotationHandler(WebAppContext context)
    {
        super(context);
    }

    public ServerEndpointAnnotationHandler(WebAppContext context, List<DiscoveredAnnotation> list)
    {
        super(context,list);
    }


    @Override
    public void handle(ClassInfo info, String annotationName)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("handleClass: {}, {}, {}",info.getClassName(),annotationName);
        }

        if (!ANNOTATION_NAME.equals(annotationName))
        {
            // Not the one we are interested in
            return;
        }

        ServerEndpointAnnotation annotation = new ServerEndpointAnnotation(_context,info.getClassName(),_resource);
        addAnnotation(annotation);
    }
}
