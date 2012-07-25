// ========================================================================
// Copyright (c) 2012-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;

public abstract class AbstractNetConnector extends AbstractConnector implements Connector.NetConnector
{
    private volatile String _host;
    private volatile int _port = 0;

    protected AbstractNetConnector()
    {
    }

    protected AbstractNetConnector(int acceptors)
    {
        super(acceptors);
    }

    public void setHost(String host)
    {
        _host = host;
    }

    public String getHost()
    {
        return _host;
    }

    public void setPort(int port)
    {
        _port = port;
    }

    public int getPort()
    {
        return _port;
    }

    public int getLocalPort()
    {
        return -1;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (getName() == null)
            setName(getHost() == null ? "0.0.0.0" : getHost() + ":" + getPort());

        open();

        setName(getName() + "/" + getLocalPort());

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        try
        {
            close();
        }
        catch (IOException e)
        {
            LOG.warn(e);
        }
        super.doStop();
    }

    public void open() throws IOException
    {
    }

    public void close() throws IOException
    {
    }

    @Override
    public String toString()
    {
        return String.format("%s@%s:%d",
                getClass().getSimpleName(),
                getHost() == null ? "0.0.0.0" : getHost(),
                getLocalPort() <= 0 ? getPort() : getLocalPort());
    }
}
