package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.Parser;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class BlockingHttpConnection extends HttpConnection
{
    private static final Logger LOG = Log.getLogger(BlockingHttpConnection.class);

    private volatile boolean _handling;
    
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

        boolean progress=true; 
        try
        {
            setCurrentConnection(this);

            // do while the endpoint is open 
            // AND the connection has not changed
            while (_endp.isOpen() && connection==this)
            {
                try
                {
                    progress=false;
                    // If we are not ended then parse available
                    if (!_parser.isComplete() && !_endp.isInputShutdown())
                        progress |= _parser.parseAvailable();
                    
                    // Do we have more generating to do?
                    // Loop here because some writes may take multiple steps and
                    // we need to flush them all before potentially blocking in the
                    // next loop.
                    if (_generator.isCommitted() && !_generator.isComplete() && !_endp.isOutputShutdown())
                        progress |= _generator.flushBuffer()>0;
                    
                    // Flush buffers
                    if (_endp.isBufferingOutput())
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
                    if (_parser.isComplete() && _generator.isComplete() && !_endp.isBufferingOutput())
                    {
                        // Reset the parser/generator
                        progress=true;
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
                            System.err.println("Safety net oshut!!!");
                            _endp.shutdownOutput();
                        }
                    }                    
                }
            }
        }
        finally
        {
            setCurrentConnection(null);
            _parser.returnBuffers();
            _generator.returnBuffers();
        }
        return connection;
    }

}
