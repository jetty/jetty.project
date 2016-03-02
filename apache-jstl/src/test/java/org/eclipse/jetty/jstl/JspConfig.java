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

package org.eclipse.jetty.jstl;

import java.io.File;
import java.net.URI;

import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Attempt at collecting up all of the JSP specific configuration bits and pieces into a single place
 * for WebAppContext users to utilize.
 */
public class JspConfig
{
    public static void init(WebAppContext context, URI baseUri, File scratchDir)
    {
        context.setAttribute("javax.servlet.context.tempdir", scratchDir);
        context.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
 ".*/javax.servlet-[^/]*\\.jar$|.*/servlet-api-[^/]*\\.jar$|.*javax.servlet.jsp.jstl-[^/]*\\.jar|.*taglibs-standard-impl-.*\\.jar");
        context.setWar(baseUri.toASCIIString());
        context.setResourceBase(baseUri.toASCIIString());
    }
}
