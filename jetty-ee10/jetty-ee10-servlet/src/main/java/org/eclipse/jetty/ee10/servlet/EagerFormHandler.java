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

package org.eclipse.jetty.ee10.servlet;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;

/**
 * Handler to eagerly and asynchronously read and parse {@link MimeTypes.Type#FORM_ENCODED} and
 * {@link MimeTypes.Type#MULTIPART_FORM_DATA} content prior to invoking the {@link ServletHandler},
 * which can then consume them with blocking APIs but without blocking.
 * @deprecated use {@link org.eclipse.jetty.server.handler.EagerContentHandler}
 */
@Deprecated(forRemoval = true, since = "12.1.0")
public class EagerFormHandler extends org.eclipse.jetty.ee.servlet.EagerFormHandler
{
    public EagerFormHandler()
    {
        super();
    }

    public EagerFormHandler(Handler handler)
    {
        super(handler);
    }
}
