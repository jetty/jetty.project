//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import javax.servlet.http.HttpSessionEvent;

/**
 * TestHttpSessionListenerWithWebappClasses
 *
 * A session listener class that checks that sessionDestroyed
 * events can reference classes known only to the webapp, ie
 * that the calling thread has been correctly annointed with
 * the webapp loader.
 */
public class TestHttpSessionListenerWithWebappClasses extends TestHttpSessionListener
{
    public TestHttpSessionListenerWithWebappClasses()
    {
        super();
    }

    public TestHttpSessionListenerWithWebappClasses(boolean attribute, boolean lastAccessTime)
    {
        super(attribute, lastAccessTime);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se)
    {
        //try loading a class that is known only to the webapp
        //to test that the calling thread has been properly
        //annointed with the webapp's classloader
        try
        {
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass("Foo");
        }
        catch (Exception cnfe)
        {
            attributeException = cnfe;
        }
        super.sessionDestroyed(se);
    }
}
