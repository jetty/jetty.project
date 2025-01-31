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

package org.eclipse.jetty.ee9.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.ClassVisibilityChecker;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollators;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClassLoader for HttpContext.
 * <p>
 * Specializes URLClassLoader with some utility and file mapping
 * methods.
 * <p>
 * This loader defaults to the 2.3 servlet spec behavior where non
 * system classes are loaded from the classpath in preference to the
 * parent loader.  Java2 compliant loading, where the parent loader
 * always has priority, can be selected with the
 * {@link WebAppContext#setParentLoaderPriority(boolean)}
 * method and influenced with {@link WebAppContext#isHiddenClass(Class)} and
 * {@link WebAppContext#isProtectedClass(Class)}.
 * <p>
 * If no parent class loader is provided, then the current thread
 * context classloader will be used.  If that is null then the
 * classloader that loaded this class is used as the parent.
 * @deprecated use the core {@link org.eclipse.jetty.ee.WebAppClassLoader} directly instead.
 */
@Deprecated(since = "12.0.0", forRemoval = true)
public class WebAppClassLoader extends org.eclipse.jetty.ee.WebAppClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    public WebAppClassLoader(org.eclipse.jetty.ee.WebAppClassLoader.Context context)
    {
        super(context);
    }

    public WebAppClassLoader(ClassLoader parent, org.eclipse.jetty.ee.WebAppClassLoader.Context context)
    {
        super(parent, context);
    }

    /**
     * Run an action with access to ServerClasses
     * <p>Run the passed {@link PrivilegedExceptionAction} with the classloader
     * configured so as to allow server classes to be visible</p>
     *
     * @param action The action to run
     * @param <T> the type of PrivilegedExceptionAction
     * @return The return from the action
     * @throws Exception if thrown by the action
     * @deprecated use {@link org.eclipse.jetty.ee.WebAppClassLoader#runWithHiddenClassAccess(PrivilegedExceptionAction)} instead
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public static <T> T runWithServerClassAccess(PrivilegedExceptionAction<T> action) throws Exception
    {
        return org.eclipse.jetty.ee.WebAppClassLoader.runWithHiddenClassAccess(action);
    }
}
