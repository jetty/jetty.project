//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Response;

public class StreamResponseListener extends Response.Listener.Adapter
{
    public Response get(long timeout, TimeUnit seconds)
    {
        return null;
    }

    public InputStream getInputStream()
    {
        return null;
    }

    public void writeTo(OutputStream outputStream)
    {
    }
}
