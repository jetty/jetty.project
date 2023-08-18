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

package org.eclipse.jetty.server;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>A context for handling an {@link Request}.
 * Every request has a non-{@code null} context, which may initially
 * be the {@link Server#getContext() server context}, or
 * a context provided by a {@link ContextHandler}.
 * A {@code Context}:</p>
 * <ul>
 *     <li>has an optional {@link #getContextPath() context path} that is a prefix to all URIs handled by the {@code Context}</li>
 *     <li>has an optional list of {@link #getVirtualHosts() virtual hosts} that the {@code Context} is applicable to.</li>
 *     <li>has an optional {@link ClassLoader} that that is set as the {@link Thread#setContextClassLoader(ClassLoader) Thread context ClassLoader}
 *         for {@link Thread}s handling the request.</li>
 *     <li>is an {@link java.util.concurrent.Executor} that can execute jobs in with the {@link Thread#setContextClassLoader(ClassLoader) Thread context ClassLoader}</li>
 *     <li>is a {@link org.eclipse.jetty.util.Decorator} using the {@link DecoratedObjectFactory } that can create objects specific to the context.</li>
 *     <li>has the same {@link #getTempDirectory() temporary director} specific to the context.</li>
 * </ul>
 */
public interface Context extends Attributes, Decorator, Executor
{
    /**
     * @return the encoded context path of this {@code Context} or
     *         {@code null} if the context does not have a path
     *         (e.g. the {@link org.eclipse.jetty.server.Server.ServerContext}).
     */
    String getContextPath();

    /**
     * @return the {@link ClassLoader} associated with this Context
     */
    ClassLoader getClassLoader();

    /**
     * @return the base resource used to lookup other resources
     * specified by the request URI path
     */
    Resource getBaseResource();

    /**
     * @return the error {@link Request.Handler} associated with this Context
     */
    Request.Handler getErrorHandler();

    /**
     * @return a list of virtual host names associated with this Context
     */
    List<String> getVirtualHosts();

    /**
     * @return the mime types associated with this Context
     */
    MimeTypes getMimeTypes();

    /**
     * <p>Executes the given task in a thread scoped to this Context.</p>
     *
     * @param task the task to run
     * @see #run(Runnable)
     */
    @Override
    void execute(Runnable task);

    /**
     * <p>Runs the given task in the current thread scoped to this Context.</p>
     *
     * @param task the task to run
     * @see #run(Runnable, Request)
     */
    void run(Runnable task);
    
    /**
     * <p>Runs the given task in the current thread scoped to this Context and the given Request.</p>
     *
     * @param task the task to run
     * @param request the HTTP request to use in the scope
     */
    void run(Runnable task, Request request);

    /**
     * <p>Returns the URI path scoped to this Context.</p>
     * @see #getPathInContext(String, String)
     * @param canonicallyEncodedPath a full URI path that should be canonically encoded as
     *        per {@link org.eclipse.jetty.util.URIUtil#canonicalPath(String)}
     * @return the URI path scoped to this Context, or {@code null} if the full path does not match this Context.
     *         The empty string is returned if the full path is exactly the context path.
     */
    default String getPathInContext(String canonicallyEncodedPath)
    {
        return getPathInContext(getContextPath(), canonicallyEncodedPath);
    }

    /**
     * @return a non-{@code null} temporary directory, configured either for the context, the server or the JVM
     */
    File getTempDirectory();

    /**
     * <p>Returns the URI path scoped to the passed context path.</p>
     * <p>For example, if the context path passed is {@code /ctx} then a
     * path of {@code /ctx/foo/bar} will return {@code /foo/bar}.</p>
     *
     * @param encodedContextPath The context path that should be canonically encoded as
     *        per {@link org.eclipse.jetty.util.URIUtil#canonicalPath(String)}.
     * @param encodedPath a full URI path that should be canonically encoded as
     *        per {@link org.eclipse.jetty.util.URIUtil#canonicalPath(String)}.
     * @return the URI {@code encodedPath} scoped to the {@code encodedContextPath},
     *         or {@code null} if the {@code encodedPath} does not match the context.
     *         The empty string is returned if the {@code encodedPath} is exactly the {@code encodedContextPath}.
     */
    static String getPathInContext(String encodedContextPath, String encodedPath)
    {
        if (encodedContextPath.length() == 0 || "/".equals(encodedContextPath))
            return encodedPath;
        if (encodedContextPath.length() > encodedPath.length() || !encodedPath.startsWith(encodedContextPath))
            return null;
        if (encodedPath.length() == encodedContextPath.length())
            return "";
        if (encodedPath.charAt(encodedContextPath.length()) != '/')
            return null;
        return encodedPath.substring(encodedContextPath.length());
    }
}
