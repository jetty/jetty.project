package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncHttpConnection extends HttpConnection
{
    private static final Logger LOG = Log.getLogger(AsyncHttpConnection.class);

    public AsyncHttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        super(connector,endpoint,server);
    }

    public Connection handle() throws IOException
    {
        Connection connection = this;
        
        // Loop while more in buffer
        try
        {
            setCurrentConnection(this);

            boolean progress=true; 
            boolean more_in_buffer =false;
            
            while (_endp.isOpen() && (more_in_buffer || progress))
            {
                progress=false;
                try
                {
                    LOG.debug("async request",_request);
                    
                    // Handle resumed request
                    if (_request._async.isAsync() && !_request._async.isComplete())
                        handleRequest();
                    
                    // else Parse more input
                    else if (!_parser.isComplete() && _parser.parseAvailable()>0)
                        progress=true;

                    // Generate more output
                    if (_generator.isCommitted() && !_generator.isComplete() && _generator.flushBuffer()>0)
                        progress=true;
                    
                    // Flush output from buffering endpoint
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
                    _endp.close();
                }
                finally
                {
                    // Do we need to complete a half close?
                    if (_endp.isInputShutdown() && (_parser.isIdle() || _parser.isComplete()))
                    {
                        LOG.debug("complete half close {}",this);
                        more_in_buffer=false;
                        _endp.close();
                        reset(true);
                    }
                    
                    // else Is this request/response round complete?
                    else if (_parser.isComplete() && _generator.isComplete() && !_endp.isBufferingOutput())
                    {
                        // look for a switched connection instance?
                        if (_response.getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        {
                            Connection switched=(Connection)_request.getAttribute("org.eclipse.jetty.io.Connection");
                            if (switched!=null)
                            {
                                _parser.reset();
                                _generator.reset(true);
                                return switched;
                            }
                        }
                        
                        // Reset the parser/generator
                        // keep the buffers as we will cycle 
                        progress=true;
                        reset(false);
                        more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();
                    }

                    // else Are we suspended?
                    else if (_request.isAsyncStarted())
                    {
                        LOG.debug("suspended {}",this);
                        more_in_buffer=false;
                        progress=false;
                    }
                    else
                        more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();
                }
            }
        }
        finally
        {
            setCurrentConnection(null);
            _parser.returnBuffers();

            // Are we write blocked
            if (_generator.isCommitted() && !_generator.isComplete())
                ((AsyncEndPoint)_endp).scheduleWrite();
            else
                _generator.returnBuffers();
        }
        return connection;
    }

}
