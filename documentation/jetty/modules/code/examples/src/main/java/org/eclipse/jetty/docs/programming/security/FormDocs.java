//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.security;

import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;

public class FormDocs
{
    public void limitFormContent()
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        // tag::limitFormContent[]
        int maxFormKeys = 100;
        int maxFormSizeInBytes = 1024;
        servletContextHandler.setMaxFormContentSize(maxFormSizeInBytes);
        servletContextHandler.setMaxFormKeys(maxFormKeys);
        // end::limitFormContent[]
    }

    public void jettyCoreAPI()
    {
        Request request = null;
        // tag::jettyCoreAPI[]
        int maxFormKeys = 100;
        int maxFormSizeInBytes = 1024;
        Fields fields;

        // Explicit set the form limits.
        fields = FormFields.getFields(request, maxFormKeys, maxFormSizeInBytes);

        // Rely on default form limits.
        fields = FormFields.getFields(request);
        // end::jettyCoreAPI[]
    }
}
