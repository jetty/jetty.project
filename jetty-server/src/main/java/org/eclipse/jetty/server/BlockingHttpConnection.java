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

package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.Parser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** Blocking Server HTTP Connection
 */
public class BlockingHttpConnection extends AbstractHttpConnection
{
    private static final Logger LOG = Log.getLogger(BlockingHttpConnection.class);

    public BlockingHttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        super(connector,endpoint,server);
    }

    public BlockingHttpConnection(Connector connector, EndPoint endpoint, Server server, Parser parser, Generator generator, Request request)
    {
        super(connector,endpoint,server,parser,generator,request);
    }

    @Override
    protected void handleRequest() throws IOException
    {
        super.handleRequest();
    }

    public Connection handle() throws IOException
    {
        Connection connection = this;

        try
        {
            setCurrentConnection(this);

            // do while the endpoint is open
            // AND the connection has not changed
            while (_endp.isOpen() && connection==this)
            {
                try
                {
                    // If we are not ended then parse available
                    if (!_parser.isComplete() && !_endp.isInputShutdown())
                        _parser.parseAvailable();

                    // Do we have more generating to do?
                    // Loop here because some writes may take multiple steps and
                    // we need to flush them all before potentially blocking in the
                    // next loop.
                    if (_generator.isCommitted() && !_generator.isComplete() && !_endp.isOutputShutdown())
                        _generator.flushBuffer();

                    // Flush buffers
                    _endp.flush();
                }
                catch (HttpException e)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("uri="+_uri);
                        LOG.debug("fields="+_requestFields);
                        LOG.debug(e);
                    }
                    _generator.sendError(e.getStatus(), e.getReason(), null, true);
                    _parser.reset();
                    _endp.shutdownOutput();
                }
                finally
                {
                    //  Is this request/response round complete and are fully flushed?
                    if (_parser.isComplete() && _generator.isComplete())
                    {
                        // Reset the parser/generator
                        reset();

                        // look for a switched connection instance?
                        if (_response.getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        {
                            Connection switched=(Connection)_request.getAttribute("org.eclipse.jetty.io.Connection");
                            if (switched!=null)
                                connection=switched;
                        }

                        // TODO Is this required?
                        if (!_generator.isPersistent() && !_endp.isOutputShutdown())
                        {
                            LOG.warn("Safety net oshut!!! Please open a bugzilla");
                            _endp.shutdownOutput();
                        }
                    }
                    
                    // If we don't have a committed response and we are not suspended
                    if (_endp.isInputShutdown() && _generator.isIdle() && !_request.getAsyncContinuation().isSuspended())
                    {
                        // then no more can happen, so close.
                        _endp.close();
                    }
                }
            }

            return connection;
        }
        finally
        {
            setCurrentConnection(null);
            _parser.returnBuffers();
            _generator.returnBuffers();
        }
    }
}
