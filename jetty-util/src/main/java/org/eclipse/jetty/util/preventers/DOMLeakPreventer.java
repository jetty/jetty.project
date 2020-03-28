//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.preventers;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * DOMLeakPreventer
 *
 * See http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6916498
 *
 * Prevent the RuntimeException that is a static member of AbstractDOMParser
 * from pinning a webapp classloader by causing it to be set here by a non-webapp classloader.
 *
 * Note that according to the bug report, a heap dump may not identify the GCRoot, making
 * it difficult to identify the cause of the leak.
 */
public class DOMLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try
        {
            factory.newDocumentBuilder();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to ping document builder", e);
        }
    }
}
