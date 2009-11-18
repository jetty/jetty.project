//========================================================================
//Copyright 2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.eclipse.jetty.server.handler; 

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.RolloverFileOutputStream;


/** 
 * Debug Handler.
 * A lightweight debug handler that can be used in production code.
 * Details of the request and response are written to an output stream
 * and the current thread name is updated with information that will link
 * to the details in that output.
 */
public class DebugHandler extends HandlerWrapper
{
    private DateCache _date=new DateCache("HH:mm:ss", Locale.US);
    private OutputStream _out;
    private PrintStream _print;
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.Handler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        final Response base_response = baseRequest.getResponse();
        final Thread thread=Thread.currentThread();
        final String old_name=thread.getName();

        boolean suspend=false;
        boolean retry=false;
        String name=(String)request.getAttribute("org.mortbay.jetty.thread.name");
        if (name==null)
            name=old_name+":"+baseRequest.getScheme()+"://"+baseRequest.getLocalAddr()+":"+baseRequest.getLocalPort()+baseRequest.getUri();
        else
            retry=true;
        
        String ex=null;
        try
        {
            final String d=_date.now();
            final int ms=_date.lastMs();
            
            if (retry)
                _print.println(d+(ms>99?".":(ms>9?".0":".00"))+ms+":"+name+" RETRY");
            else
                _print.println(d+(ms>99?".":(ms>9?".0":".00"))+ms+":"+name+" "+baseRequest.getRemoteAddr()+" "+request.getMethod()+" "+baseRequest.getHeader("Cookie")+"; "+baseRequest.getHeader("User-Agent"));
            thread.setName(name);
            
            getHandler().handle(target,baseRequest,request,response);
        }
        catch(IOException ioe)
        {
            ex=ioe.toString();
            throw ioe;
        }
        catch(ServletException se)
        {
            ex=se.toString()+":"+se.getCause();
            throw se;
        }
        catch(RuntimeException rte)
        {
            ex=rte.toString();
            throw rte;
        }
        catch(Error e)
        {
            ex=e.toString();
            throw e;
        }
        finally
        {
            thread.setName(old_name);
            final String d=_date.now();
            final int ms=_date.lastMs();
            suspend=baseRequest.getAsyncContinuation().isSuspended();
            if (suspend)
            {
                request.setAttribute("org.mortbay.jetty.thread.name",name);
                _print.println(d+(ms>99?".":(ms>9?".0":".00"))+ms+":"+name+" SUSPEND");
            }
            else
                _print.println(d+(ms>99?".":(ms>9?".0":".00"))+ms+":"+name+" "+base_response.getStatus()+
		        (ex==null?"":("/"+ex))+
		        " "+base_response.getContentType()+" "+base_response.getContentCount());
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_out==null)
            _out=new RolloverFileOutputStream("./logs/yyyy_mm_dd.debug.log",true);
        _print=new PrintStream(_out);
        super.doStart();
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _print.close();
    }

    /**
     * @return the out
     */
    public OutputStream getOutputStream()
    {
        return _out;
    }

    /**
     * @param out the out to set
     */
    public void setOutputStream(OutputStream out)
    {
        _out = out;
    }
}
