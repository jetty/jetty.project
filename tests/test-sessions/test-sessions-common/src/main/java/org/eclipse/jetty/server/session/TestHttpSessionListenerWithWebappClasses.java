//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
