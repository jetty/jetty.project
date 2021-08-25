//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.transformer;

import java.io.IOException;
import java.util.Iterator;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.webapp.WebAppContext;

public class JavaxWebAppContext extends WebAppContext
{

    private static final String JAVAX_ATT_NAME_PREFIX = "javax.servlet";

    private static final String JAKARTA_ATT_NAME_PREFIX = "jakarta.servlet";

    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        transformAttributeNames(request);
        super.doHandle(target, baseRequest, request, response);
    }

    @Override
    public void handle(Request request, Runnable runnable)
    {
        transformAttributeNames(request);
        super.handle(request, runnable);
    }

    private void transformAttributeNames(HttpServletRequest request)
    {
        for (Iterator<String> it = request.getAttributeNames().asIterator(); it.hasNext();)
        {
            String attributeName = it.next();
            if (attributeName.startsWith(JAVAX_ATT_NAME_PREFIX))
            {
                // we create a new attribute with the new namespace but we do not remove the previous
                // just in case the webapp is using it
                request.setAttribute(JAKARTA_ATT_NAME_PREFIX + attributeName.substring(JAVAX_ATT_NAME_PREFIX.length()),
                        request.getAttribute(attributeName));
            }

        }
    }

}
