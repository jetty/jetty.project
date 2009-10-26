// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.webdav;

import java.io.IOException;

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.log.Log;


public class WebdavSupportedExchange extends HttpExchange
{
    private boolean _webdavSupported = false;
    private boolean _isComplete = false;

    @Override
    protected void onResponseHeader(Buffer name, Buffer value) throws IOException
    {
        if (Log.isDebugEnabled())
            Log.debug("WebdavSupportedExchange:Header:" + name.toString() + " / " + value.toString() );
        if ( "DAV".equals( name.toString() ) )
        {
            if ( value.toString().indexOf( "1" ) >= 0 || value.toString().indexOf( "2" ) >= 0 )
            {
                _webdavSupported = true;
            }
        }

        super.onResponseHeader(name, value);
    }

    public void waitTilCompletion() throws InterruptedException
    {
        synchronized (this)
        {
            while ( !_isComplete)
            {
                this.wait();
            }
        }
    }

    @Override
    protected void onResponseComplete() throws IOException
    {
        _isComplete = true;

        super.onResponseComplete();
    }

    public boolean isWebdavSupported()
    {
        return _webdavSupported;
    }
}
