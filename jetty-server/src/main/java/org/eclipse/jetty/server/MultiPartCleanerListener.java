//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;

import org.eclipse.jetty.server.handler.ContextHandler;

public class MultiPartCleanerListener implements ServletRequestListener
{
    public static final MultiPartCleanerListener INSTANCE = new MultiPartCleanerListener();

    protected MultiPartCleanerListener()
    {
    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre)
    {
        //Clean up any tmp files created by MultiPartInputStream
        MultiParts parts = (MultiParts)sre.getServletRequest().getAttribute(Request.MULTIPARTS);
        if (parts != null)
        {
            ContextHandler.Context context = parts.getContext();

            //Only do the cleanup if we are exiting from the context in which a servlet parsed the multipart files
            if (context == sre.getServletContext())
            {
                try
                {
                    parts.close();
                }
                catch (Throwable e)
                {
                    sre.getServletContext().log("Errors deleting multipart tmp files", e);
                }
            }
        }
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre)
    {
        //nothing to do, multipart config set up by ServletHolder.handle()
    }
}
