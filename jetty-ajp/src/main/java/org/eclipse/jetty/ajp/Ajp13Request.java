// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.ajp;

import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;

public class Ajp13Request extends Request
{
    protected String _remoteAddr;
    protected String _remoteHost;
    protected String _remoteUser;
    protected boolean _sslSecure;

    /* ------------------------------------------------------------ */
    public Ajp13Request(HttpConnection connection)
    {
        super(connection);     
    }
    
    /* ------------------------------------------------------------ */
    public Ajp13Request()
    {     
    }

    /* ------------------------------------------------------------ */
    void setConnection(Ajp13Connection connection)
    {
        super.setConnection(connection);
    }

    /* ------------------------------------------------------------ */
    public void setRemoteUser(String remoteUser)
    {
        _remoteUser = remoteUser;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getRemoteUser()
    {
        if(_remoteUser != null)
            return _remoteUser;
        return super.getRemoteUser();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getRemoteAddr()
    {
        if (_remoteAddr != null)
            return _remoteAddr;
        if (_remoteHost != null)
            return _remoteHost;
        return super.getRemoteAddr();
    }



    /* ------------------------------------------------------------ */
    @Override
    public void setRemoteAddr(String remoteAddr)
    {
        _remoteAddr = remoteAddr;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getRemoteHost()
    {
        if (_remoteHost != null)
            return _remoteHost;
        if (_remoteAddr != null)
            return _remoteAddr;
        return super.getRemoteHost();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setRemoteHost(String remoteHost)
    {
        _remoteHost = remoteHost;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isSslSecure()
    {
        return _sslSecure;
    }

    /* ------------------------------------------------------------ */
    public void setSslSecure(boolean sslSecure)
    {
        _sslSecure = sslSecure;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void recycle()
    {
        super.recycle();
        _remoteAddr = null;
        _remoteHost = null;
	_remoteUser = null;
	_sslSecure = false;
    }

}
