//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.webdav;

import java.io.IOException;

import org.eclipse.jetty.client.CachedExchange;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public class MkcolExchange extends CachedExchange
{
    private static final Logger LOG = Log.getLogger(MkcolExchange.class);

    boolean exists = false;

    public MkcolExchange()
    {
        super(true);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
        if ( status == HttpStatus.CREATED_201 )
        {
            LOG.debug( "MkcolExchange:Status: Successfully created resource" );
            exists = true;
        }

        if ( status == HttpStatus.METHOD_NOT_ALLOWED_405 ) // returned when resource exists
        {
            LOG.debug( "MkcolExchange:Status: Resource must exist" );
            exists = true;
        }

        super.onResponseStatus(version, status, reason);
    }

    public boolean exists()
    {
        return exists;
    }
}
