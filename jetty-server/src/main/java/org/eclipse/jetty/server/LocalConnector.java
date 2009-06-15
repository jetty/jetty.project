// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.StringUtil;

public class LocalConnector extends AbstractConnector
{
    ByteArrayEndPoint _endp;
    ByteArrayBuffer _in;
    ByteArrayBuffer _out;
    
    Server _server;
    boolean _accepting;
    boolean _keepOpen;
    
    public LocalConnector()
    {
        setPort(1);
    }

    /* ------------------------------------------------------------ */
    public Object getConnection()
    {
        return _endp;
    }
    

    /* ------------------------------------------------------------ */
    public void setServer(Server server)
    {
        super.setServer(server);
        this._server=server;
    }

    /* ------------------------------------------------------------ */
    public void clear()
    {
        _in.clear();
        _out.clear();
    }

    /* ------------------------------------------------------------ */
    public void reopen()
    {
        _in.clear();
        _out.clear();
        _endp = new ByteArrayEndPoint();
        _endp.setIn(_in);
        _endp.setOut(_out);
        _endp.setGrowOutput(true);
        _accepting=false;
    }

    /* ------------------------------------------------------------ */
    public void doStart()
        throws Exception
    {   
        _in=new ByteArrayBuffer(8192);
        _out=new ByteArrayBuffer(8192);
        _endp = new ByteArrayEndPoint();
        _endp.setIn(_in);
        _endp.setOut(_out);
        _endp.setGrowOutput(true);
        _accepting=false;
        
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    public String getResponses(String requests)
        throws Exception
    {
        return getResponses(requests,false);
    }
    
    /* ------------------------------------------------------------ */
    public String getResponses(String requests, boolean keepOpen)
    throws Exception
    {
        // System.out.println("\nREQUESTS :\n"+requests);
        // System.out.flush();
        ByteArrayBuffer buf=new ByteArrayBuffer(requests,StringUtil.__ISO_8859_1);
        if (_in.space()<buf.length())
        {
            ByteArrayBuffer n = new ByteArrayBuffer(_in.length()+buf.length());
            n.put(_in);
            _in=n;
            _endp.setIn(_in);
        }
        _in.put(buf);
        
        synchronized (this)
        {
            _keepOpen=keepOpen;
            _accepting=true;
            this.notify();
            
            while(_accepting)
                this.wait();
        }
        
        // System.err.println("\nRESPONSES:\n"+out);
        _out=_endp.getOut();
        return _out.toString(StringUtil.__ISO_8859_1);
    }
    
    /* ------------------------------------------------------------ */
    public ByteArrayBuffer getResponses(ByteArrayBuffer buf, boolean keepOpen)
    throws Exception
    {
        if (_in.space()<buf.length())
        {
            ByteArrayBuffer n = new ByteArrayBuffer(_in.length()+buf.length());
            n.put(_in);
            _in=n;
            _endp.setIn(_in);
        }
        _in.put(buf);
        
        synchronized (this)
        {
            _keepOpen=keepOpen;
            _accepting=true;
            this.notify();
            
            while(_accepting)
                this.wait();
        }
        
        // System.err.println("\nRESPONSES:\n"+out);
        _out=_endp.getOut();
        return _out;
    }

    /* ------------------------------------------------------------ */
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        HttpConnection connection=null;
        
        while (isRunning())
        {
            synchronized (this)
            {
                try
                {
                    while(!_accepting)
                        this.wait();
                }
                catch(InterruptedException e)
                {
                    return;
                }
            }
            
            try
            {
                if (connection==null)
                {
                    connection=new HttpConnection(this,_endp,getServer());
                    connectionOpened(connection);
                }
                while (_in.length()>0)
                    connection.handle();
            }
            finally
            {
                if (!_keepOpen)
                {
                    connectionClosed(connection);
                    connection=null;
                }
                synchronized (this)
                {
                    _accepting=false;
                    this.notify();
                }
            }
        }
    }
    

    public void open() throws IOException
    {
    }

    public void close() throws IOException
    {
    }

    /* ------------------------------------------------------------------------------- */
    public int getLocalPort()
    {
        return -1;
    }

    
}
