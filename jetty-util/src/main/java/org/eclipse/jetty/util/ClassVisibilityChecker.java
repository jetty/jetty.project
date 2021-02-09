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

package org.eclipse.jetty.util;

/**
 * ClassVisibilityChecker
 *
 * Interface to be implemented by classes capable of checking class visibility
 * for a context.
 */
public interface ClassVisibilityChecker
{

    /**
     * Is the class a System Class.
     * A System class is a class that is visible to a webapplication,
     * but that cannot be overridden by the contents of WEB-INF/lib or
     * WEB-INF/classes
     *
     * @param clazz The fully qualified name of the class.
     * @return True if the class is a system class.
     */
    boolean isSystemClass(Class<?> clazz);

    /**
     * Is the class a Server Class.
     * A Server class is a class that is part of the implementation of
     * the server and is NIT visible to a webapplication. The web
     * application may provide it's own implementation of the class,
     * to be loaded from WEB-INF/lib or WEB-INF/classes
     *
     * @param clazz The fully qualified name of the class.
     * @return True if the class is a server class.
     */
    boolean isServerClass(Class<?> clazz);
}
