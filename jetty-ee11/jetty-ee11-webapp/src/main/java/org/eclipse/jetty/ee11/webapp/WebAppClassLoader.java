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

package org.eclipse.jetty.ee11.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.MalformedURLException;
import java.net.URI;
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
import java.util.jar.Manifest;

import org.eclipse.jetty.util.ClassVisibilityChecker;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
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
 * This loader implements the Servlet specification behavior that may invert the normal Java classloader parent priority
 * behaviour. The {@link ClassVisibilityChecker} API of the {@link WebAppClassLoader.Context} implementation is
 * used to determine which classes from the parent classloader are hidden from the context, and which are protected
 * from being overridden by the context.
 * <p>
 * Java compliant loading, where the parent loader always has priority, can be selected with the
 * {@link WebAppContext#setParentLoaderPriority(boolean)} method.
 *
 * @deprecated use the core {@link org.eclipse.jetty.ee.WebAppClassLoader} directly instead.
 */
@Deprecated(since = "12.0.0", forRemoval = true)
public class WebAppClassLoader extends org.eclipse.jetty.ee.WebAppClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    public WebAppClassLoader(Context context)
    {
        super(context);
    }

    public WebAppClassLoader(ClassLoader parent, Context context)
    {
        super(parent, context);
    }
}
