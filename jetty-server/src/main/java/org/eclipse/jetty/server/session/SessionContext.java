//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.StringUtil;

/**
 * SessionContext
 *
 * Information about the context to which sessions belong: the Context,
 * the SessionHandler of the context, and the unique name of the node.
 *
 * A SessionHandler is 1:1 with a SessionContext.
 */
public class SessionContext
{
    public static final String NULL_VHOST = "0.0.0.0";
    private ContextHandler.Context _context;
    private SessionHandler _sessionHandler;
    private String _workerName;
    private String _canonicalContextPath;
    private String _vhost;

    public SessionContext(String workerName, ContextHandler.Context context)
    {
        if (context != null)
            _sessionHandler = context.getContextHandler().getChildHandlerByClass(SessionHandler.class);
        _workerName = workerName;
        _context = context;
        _canonicalContextPath = canonicalizeContextPath(_context);
        _vhost = canonicalizeVHost(_context);
    }

    public String getWorkerName()
    {
        return _workerName;
    }

    public SessionHandler getSessionHandler()
    {
        return _sessionHandler;
    }

    public Context getContext()
    {
        return _context;
    }

    public String getCanonicalContextPath()
    {
        return _canonicalContextPath;
    }

    public String getVhost()
    {
        return _vhost;
    }

    @Override
    public String toString()
    {
        return _workerName + "_" + _canonicalContextPath + "_" + _vhost;
    }

    /**
     * Run a runnable in the context (with context classloader set) if
     * there is one, otherwise just run it.
     *
     * @param r the runnable
     */
    public void run(Runnable r)
    {
        if (_context != null)
            _context.getContextHandler().handle(r);
        else
            r.run();
    }

    private String canonicalizeContextPath(Context context)
    {
        if (context == null)
            return "";
        return canonicalize(context.getContextPath());
    }

    /**
     * Get the first virtual host for the context.
     *
     * Used to help identify the exact session/contextPath.
     *
     * @return 0.0.0.0 if no virtual host is defined
     */
    private String canonicalizeVHost(Context context)
    {
        String vhost = NULL_VHOST;

        if (context == null)
            return vhost;

        String[] vhosts = context.getContextHandler().getVirtualHosts();
        if (vhosts == null || vhosts.length == 0 || vhosts[0] == null)
            return vhost;

        return vhosts[0];
    }

    /**
     * Make an acceptable name from a context path.
     *
     * @param path the path to normalize/fix
     * @return the clean/acceptable form of the path
     */
    private String canonicalize(String path)
    {
        if (path == null)
            return "";

        return StringUtil.sanitizeFileSystemName(path);
    }
}
