package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.HandlerWrapper;

class SuspendHandler extends HandlerWrapper
{
    private int _read;
    private long _suspendFor=-1;
    private long _resumeAfter=-1;
    private long _completeAfter=-1;
    private long _suspendFor2=-1;
    private long _resumeAfter2=-1;
    private long _completeAfter2=-1;

    public SuspendHandler()
    {
    }

    public int getRead()
    {
        return _read;
    }

    public void setRead(int read)
    {
        _read = read;
    }

    public long getSuspendFor()
    {
        return _suspendFor;
    }

    public void setSuspendFor(long suspendFor)
    {
        _suspendFor = suspendFor;
    }

    public long getResumeAfter()
    {
        return _resumeAfter;
    }

    public void setResumeAfter(long resumeAfter)
    {
        _resumeAfter = resumeAfter;
    }

    public long getCompleteAfter()
    {
        return _completeAfter;
    }

    public void setCompleteAfter(long completeAfter)
    {
        _completeAfter = completeAfter;
    }
    
    
    
    /* ------------------------------------------------------------ */
    /** Get the suspendFor2.
     * @return the suspendFor2
     */
    public long getSuspendFor2()
    {
        return _suspendFor2;
    }


    /* ------------------------------------------------------------ */
    /** Set the suspendFor2.
     * @param suspendFor2 the suspendFor2 to set
     */
    public void setSuspendFor2(long suspendFor2)
    {
        _suspendFor2 = suspendFor2;
    }


    /* ------------------------------------------------------------ */
    /** Get the resumeAfter2.
     * @return the resumeAfter2
     */
    public long getResumeAfter2()
    {
        return _resumeAfter2;
    }


    /* ------------------------------------------------------------ */
    /** Set the resumeAfter2.
     * @param resumeAfter2 the resumeAfter2 to set
     */
    public void setResumeAfter2(long resumeAfter2)
    {
        _resumeAfter2 = resumeAfter2;
    }


    /* ------------------------------------------------------------ */
    /** Get the completeAfter2.
     * @return the completeAfter2
     */
    public long getCompleteAfter2()
    {
        return _completeAfter2;
    }


    /* ------------------------------------------------------------ */
    /** Set the completeAfter2.
     * @param completeAfter2 the completeAfter2 to set
     */
    public void setCompleteAfter2(long completeAfter2)
    {
        _completeAfter2 = completeAfter2;
    }


    @Override
    public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
    {
        try
        {
            if (DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
            {
                if (_read>0)
                {
                    byte[] buf=new byte[_read];
                    request.getInputStream().read(buf);
                }
                else if (_read<0)
                {
                    InputStream in = request.getInputStream();
                    int b=in.read();
                    while(b!=-1)
                        b=in.read();
                }


                final AsyncContext asyncContext = baseRequest.startAsync();
                // System.err.println("STARTASYNC");
                response.getOutputStream().println("STARTASYNC");
                asyncContext.addListener(LocalAsyncContextTest.__asyncListener);
                asyncContext.addListener(LocalAsyncContextTest.__asyncListener1);
                if (_suspendFor>0)
                    asyncContext.setTimeout(_suspendFor);


                if (_completeAfter>0)
                {
                    new Thread() {
                        @Override
                        public void run()
                        {
                            try
                            {
                                Thread.sleep(_completeAfter);
                                //System.err.println("COMPLETED");
                                response.getOutputStream().println("COMPLETED");
                                response.setStatus(200);
                                baseRequest.setHandled(true);
                                asyncContext.complete();
                            }
                            catch(Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }
                else if (_completeAfter==0)
                {
                    //System.err.println("COMPLETED0");
                    response.getOutputStream().println("COMPLETED");
                    response.setStatus(200);
                    baseRequest.setHandled(true);
                    asyncContext.complete();
                }

                if (_resumeAfter>0)
                {
                    new Thread() {
                        @Override
                        public void run()
                        {
                            try
                            {
                                Thread.sleep(_resumeAfter);
                                if(((HttpServletRequest)asyncContext.getRequest()).getSession(true).getId()!=null)
                                    asyncContext.dispatch();
                            }
                            catch(Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }
                else if (_resumeAfter==0)
                {
                    asyncContext.dispatch();
                }
            }
            else 
            {
                if (request.getAttribute("TIMEOUT")!=null)
                {
                    //System.err.println("TIMEOUT");
                    response.getOutputStream().println("TIMEOUT");
                }
                else
                {
                    //System.err.println("DISPATCHED");
                    response.getOutputStream().println("DISPATCHED"); 
                }

                if (_suspendFor2>=0)
                {
                    final AsyncContext asyncContext = baseRequest.startAsync();
                    //System.err.println("STARTASYNC2");
                    response.getOutputStream().println("STARTASYNC2");
                    if (_suspendFor2>0)
                        asyncContext.setTimeout(_suspendFor2);
                    _suspendFor2=-1;

                    if (_completeAfter2>0)
                    {
                        new Thread() {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    Thread.sleep(_completeAfter2);
                                    //System.err.println("COMPLETED2");
                                    response.getOutputStream().println("COMPLETED2");
                                    response.setStatus(200);
                                    baseRequest.setHandled(true);
                                    asyncContext.complete();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                    else if (_completeAfter2==0)
                    {
                        //System.err.println("COMPLETED2==0");
                        response.getOutputStream().println("COMPLETED2");
                        response.setStatus(200);
                        baseRequest.setHandled(true);
                        asyncContext.complete();
                    }

                    if (_resumeAfter2>0)
                    {
                        new Thread() {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    Thread.sleep(_resumeAfter2);
                                    asyncContext.dispatch();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                    else if (_resumeAfter2==0)
                    {
                        asyncContext.dispatch();
                    }
                }
                else
                {
                    response.setStatus(200);
                    baseRequest.setHandled(true);
                }
            }
        }
        finally
        {
        }
    }
}