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
