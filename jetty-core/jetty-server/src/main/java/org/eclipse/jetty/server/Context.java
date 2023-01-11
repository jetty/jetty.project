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

package org.eclipse.jetty.server;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.resource.Resource;

/**
 * A Context for handling/processing a request.
 * Every request will always have a non-null context, which may initially be the Server context, or
 * a context provided by a ContextHandler.
 * <p>
 * A Context is also an {@link Executor}, which allows tasks to be run by a thread pool, but scoped
 * to the classloader and any other aspects of the context.   Method {@link #run(Runnable)}
 * is also provided to allow the current Thread to be scoped to the context.
 * <p>
 * A Context is also a {@link Decorator}, allowing objects to be decorated in a context scope.
 *
 */
public interface Context extends Attributes, Decorator, Executor
{
    /**
     * @return the context path of this Context
     */
    String getContextPath();

    ClassLoader getClassLoader();

    Resource getBaseResource();

    Request.Processor getErrorProcessor();

    List<String> getVirtualHosts();

    MimeTypes getMimeTypes();

    @Override
    /** execute runnable in container thread scoped to context */
    void execute(Runnable runnable);

    /** scope the calling thread to the context and run the runnable. */
    void run(Runnable runnable);
    
    /** scope the calling thread to the context and request and run the runnable. */
    void run(Runnable runnable, Request request);

    /**
     * <p>Returns a URI path scoped to this Context.</p>
     * <p>For example, if the context path is {@code /ctx} then a
     * full path of {@code /ctx/foo/bar} will return {@code /foo/bar}.</p>
     *
     * @param fullPath A full URI path
     * @return The URI path scoped to this Context, or {@code null} if the full path does not match this Context.
     *         The empty string is returned if the full path is exactly the context path.
     */
    String getPathInContext(String fullPath);

    /**
     * @return A temporary directory, configured either for the context, the server or the JVM
     */
    File getTempDirectory();
}
