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

package org.eclipse.jetty.util;

/**
 * Interface for 3rd party libraries to decorate recently created objects in Jetty.
 * <p>
 * Most common use is weld/CDI.
 * <p>
 * This was moved from org.eclipse.jetty.servlet.ServletContextHandler to allow
 * client applications to also use Weld/CDI to decorate objects.  
 * Such as websocket client (which has no servlet api requirement)
 */
public interface Decorator
{
    <T> T decorate(T o);

    void destroy(Object o);
}
