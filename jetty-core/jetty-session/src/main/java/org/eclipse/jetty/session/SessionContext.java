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

package org.eclipse.jetty.session;

import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.util.StringUtil;

/**
 * SessionContext
 *
 * Information about the context to which sessions belong: the Context,
 * the SessionManager of the context, and the unique name of the node.
 *
 * A SessionManager is 1:1 with a SessionContext.
 */
public class SessionContext
{
    public static final String NULL_VHOST = "0.0.0.0";
    protected Context _context;
    protected SessionManager _sessionManager;
    protected String _workerName;
    protected String _canonicalContextPath;
    protected String _vhost;
    
    protected SessionContext()
    {
    }

    public SessionContext(SessionManager sessionManager)
    {
        _sessionManager = Objects.requireNonNull(sessionManager);
        _workerName = _sessionManager.getSessionIdManager().getWorkerName();
        _context = sessionManager.getContext();
        _canonicalContextPath = canonicalizeContextPath(_context);
        _vhost = canonicalizeVHost(_context);
    }

    public String getWorkerName()
    {
        return _workerName;
    }

    public SessionManager getSessionManager()
    {
        return _sessionManager;
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
        if (_context != null) // TODO I don't think this can be null anymore
            _context.run(r);
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

        List<String> vhosts = context.getVirtualHosts();
        if (vhosts == null || vhosts.isEmpty())
            return vhost;

        return vhosts.get(0);
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
