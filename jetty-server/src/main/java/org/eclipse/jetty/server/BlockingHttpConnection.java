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

public class BlockingHttpConnection extends HttpConnection
{
    private volatile boolean _handling;
    
    public BlockingHttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        super(connector,endpoint,server);
    }

    
    public BlockingHttpConnection(Connector connector, EndPoint endpoint, Server server, Parser parser, Generator generator, Request request)
    {
        super(connector,endpoint,server,parser,generator,request);
    }


    public Connection handle() throws IOException
    {
        // TODO - simplify this - as much of it is taken from the async case and does not apply to blocking.
        
        
        Connection connection = this;

        // Loop while more in buffer
        boolean more_in_buffer =true; // assume true until proven otherwise
        boolean progress=true;

        try
        {
            assert getCurrentConnection()==null;
            assert _handling==false;
            _handling=true;
            setCurrentConnection(this);

            while (more_in_buffer && _endp.isOpen())
            {
                try
                {
                    // If we are not ended then parse available
                    if (!_parser.isComplete())
                    {
                        int parsed=_parser.parseAvailable();
                        if (parsed>0)
                            progress=true;
                    }

                    // Do we have more generating to do?
                    // Loop here because some writes may take multiple steps and
                    // we need to flush them all before potentially blocking in the
                    // next loop.
                    while (_generator.isCommitted() && !_generator.isComplete())
                    {
                        long written=_generator.flushBuffer();
                        if (written<=0)
                            break;
                        progress=true;
                        if (_endp.isBufferingOutput())
                            _endp.flush();
                    }

                    // Flush buffers
                    if (_endp.isBufferingOutput())
                    {
                        _endp.flush();
                        if (!_endp.isBufferingOutput())
                            progress=true;
                    }

                    if (!progress)
                        return this;
                    progress=false;
                }
                catch (HttpException e)
                {
                    if (Log.isDebugEnabled())
                    {
                        Log.debug("uri="+_uri);
                        Log.debug("fields="+_requestFields);
                        Log.debug(e);
                    }
                    _generator.sendError(e.getStatus(), e.getReason(), null, true);

                    _parser.reset();
                    _endp.close();
                }
                finally
                {
                    more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();
                    
                    // Is this request/response round complete?
                    if (_parser.isComplete() && _generator.isComplete() && !_endp.isBufferingOutput())
                    {
                        // look for a switched connection instance?
                        Connection switched=(_response.getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        ?(Connection)_request.getAttribute("org.eclipse.jetty.io.Connection"):null;

                        // have we switched?
                        if (switched!=null)
                        {
                            _parser.reset();
                            _generator.reset(true);
                            connection=switched;
                        }
                        else
                        {
                            // No switch, so cleanup and reset
                            if (!_generator.isPersistent() || _endp.isInputShutdown())
                            {
                                _parser.reset();
                                more_in_buffer=false;
                                _endp.close();
                            }

                            if (more_in_buffer)
                            {
                                reset(false);
                                more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();
                            }
                            else
                                reset(true);
                            progress=true;
                        }
                    }
                    else if (_parser.isIdle() && _endp.isInputShutdown())
                    {
                        more_in_buffer=false;
                        _endp.close();
                    }

                    if (_request.isAsyncStarted())
                    {
                        throw new IllegalStateException();
                    }
                    else if (_generator.isCommitted() && !_generator.isComplete() && _endp instanceof AsyncEndPoint)
                        ((AsyncEndPoint)_endp).scheduleWrite();
                }
            }
        }
        finally
        {
            _parser.returnBuffers();
            setCurrentConnection(null);
            _handling=false;
        }
        return connection;
    }

}
