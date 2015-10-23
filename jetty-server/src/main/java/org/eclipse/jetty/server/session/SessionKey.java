//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.handler.ContextHandler.Context;

/**
 * SessionKey
 *
 *
 */
public class SessionKey
{
    private String _id;
    private String _canonicalContextPath;
    private String _vhost;
    

    public static SessionKey getKey (String id, Context context)
    {
        String cpath = getContextPath(context);
        String vhosts = getVirtualHost(context);
        return new SessionKey (id, cpath, vhosts);
    }
    
    public static SessionKey getKey (SessionData data)
    {
        String cpath = data.getContextPath();
        String vhost = data.getVhost();
        String id = data.getId();
        return new SessionKey(id, cpath, vhost);
    }
    
    public static SessionKey getKey (String id, String canonicalContextPath, String canonicalVirtualHost)
    {
        return new SessionKey(id, canonicalContextPath, canonicalVirtualHost);
    }
        
    
    private SessionKey (String id, String path, String vhost)
    {
        _id = id;
        _canonicalContextPath = path;
        _vhost = vhost;
    }
    
    public String getId()
    {
        return _id;
    }
    
    public String getCanonicalContextPath()
    {
        return _canonicalContextPath;
    }
    
    public String getVhost()
    {
        return _vhost;
    }
    
    public String toString ()
    {
        return _canonicalContextPath +"_"+_vhost+"_"+_id;
    }
    
    public static String getContextPath (Context context)
    {
        return canonicalize (context.getContextPath());
    }
    
    
    /**
     * Get the first virtual host for the context.
     *
     * Used to help identify the exact session/contextPath.
     *
     * @return 0.0.0.0 if no virtual host is defined
     */
    public static String getVirtualHost (Context context)
    {
        String vhost = "0.0.0.0";

        if (context==null)
            return vhost;

        String [] vhosts = context.getContextHandler().getVirtualHosts();
        if (vhosts==null || vhosts.length==0 || vhosts[0]==null)
            return vhost;

        return vhosts[0];
    }
    
    /**
     * Make an acceptable name from a context path.
     *
     * @param path
     * @return
     */
    private static String canonicalize (String path)
    {
        if (path==null)
            return "";

        return path.replace('/', '_').replace('.','_').replace('\\','_');
    }                            
}
