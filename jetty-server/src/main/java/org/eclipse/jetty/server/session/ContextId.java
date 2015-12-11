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
 * ContextId
 *
 *
 */
public class ContextId
{
    public final static String NULL_VHOST = "0.0.0.0";

    private String _node;
    private String _canonicalContextPath;
    private String _vhost;
    

    public static ContextId getContextId (String node, Context context)
    {
        return new ContextId((node==null?"":node), getContextPath(context),  getVirtualHost(context));
    }
    
    
    private ContextId (String node, String path, String vhost)
    {
        if (node == null || path == null || vhost == null)
            throw new IllegalArgumentException ("Bad values for ContextId ["+node+","+path+","+vhost+"]");

        _node = node;
        _canonicalContextPath = path;
        _vhost = vhost;
    }
    
    public String getNode()
    {
        return _node;
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
        return _node+"_"+_canonicalContextPath +"_"+_vhost;
    }
    
    @Override
    public boolean equals (Object o)
    {
        if (o == null)
            return false;
        
        ContextId id = (ContextId)o;
        if (id.getNode().equals(getNode()) && id.getCanonicalContextPath().equals(getCanonicalContextPath()) && id.getVhost().equals(getVhost()))
                return true;
        return false;
    }
    
    @Override
    public int hashCode()
    {
        return java.util.Objects.hash(getNode(), getCanonicalContextPath(), getVhost());
    }

    public static String getContextPath (Context context)
    {
        if (context == null)
            return "";
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
        String vhost = NULL_VHOST;

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
