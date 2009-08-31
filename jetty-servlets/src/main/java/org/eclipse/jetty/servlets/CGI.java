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

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;

//-----------------------------------------------------------------------------
/**
 * CGI Servlet.
 * 
 * The cgi bin directory can be set with the "cgibinResourceBase" init parameter
 * or it will default to the resource base of the context.
 * 
 * The "commandPrefix" init parameter may be used to set a prefix to all
 * commands passed to exec. This can be used on systems that need assistance to
 * execute a particular file type. For example on windows this can be set to
 * "perl" so that perl scripts are executed.
 * 
 * The "Path" init param is passed to the exec environment as PATH. Note: Must
 * be run unpacked somewhere in the filesystem.
 * 
 * Any initParameter that starts with ENV_ is used to set an environment
 * variable with the name stripped of the leading ENV_ and using the init
 * parameter value.
 * 
 * 
 * 
 */
public class CGI extends HttpServlet
{
    private boolean _ok;
    private File _docRoot;
    private String _path;
    private String _cmdPrefix;
    private EnvList _env;
    private boolean _ignoreExitState;

    /* ------------------------------------------------------------ */
    public void init() throws ServletException
    {
        _env=new EnvList();
        _cmdPrefix=getInitParameter("commandPrefix");

        String tmp=getInitParameter("cgibinResourceBase");
        if (tmp==null)
        {
            tmp=getInitParameter("resourceBase");
            if (tmp==null)
                tmp=getServletContext().getRealPath("/");
        }

        if (tmp==null)
        {
            Log.warn("CGI: no CGI bin !");
            return;
        }

        File dir=new File(tmp);
        if (!dir.exists())
        {
            Log.warn("CGI: CGI bin does not exist - "+dir);
            return;
        }

        if (!dir.canRead())
        {
            Log.warn("CGI: CGI bin is not readable - "+dir);
            return;
        }

        if (!dir.isDirectory())
        {
            Log.warn("CGI: CGI bin is not a directory - "+dir);
            return;
        }

        try
        {
            _docRoot=dir.getCanonicalFile();
        }
        catch (IOException e)
        {
            Log.warn("CGI: CGI bin failed - "+dir,e);
            return;
        }

        _path=getInitParameter("Path");
        if (_path!=null)
            _env.set("PATH",_path);

        _ignoreExitState="true".equalsIgnoreCase(getInitParameter("ignoreExitState"));
        Enumeration e=getInitParameterNames();
        while (e.hasMoreElements())
        {
            String n=(String)e.nextElement();
            if (n!=null&&n.startsWith("ENV_"))
                _env.set(n.substring(4),getInitParameter(n));
        }
        if(!_env.envMap.containsKey("SystemRoot"))
        {
      	    String os = System.getProperty("os.name");
            if (os!=null && os.toLowerCase().indexOf("windows")!=-1)
            {
        	_env.set("SystemRoot", "C:\\WINDOWS"); 
            }
        }   
      
        _ok=true;
    }

    /* ------------------------------------------------------------ */
    public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
    {
        if (!_ok)
        {
            res.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        
        String pathInContext=StringUtil.nonNull(req.getServletPath())+StringUtil.nonNull(req.getPathInfo());

        if (Log.isDebugEnabled())
        {
            Log.debug("CGI: ContextPath : "+req.getContextPath());
            Log.debug("CGI: ServletPath : "+req.getServletPath());
            Log.debug("CGI: PathInfo    : "+req.getPathInfo());
            Log.debug("CGI: _docRoot    : "+_docRoot);
            Log.debug("CGI: _path       : "+_path);
            Log.debug("CGI: _ignoreExitState: "+_ignoreExitState);
        }

        // pathInContext may actually comprises scriptName/pathInfo...We will
        // walk backwards up it until we find the script - the rest must
        // be the pathInfo;

        String both=pathInContext;
        String first=both;
        String last="";

        File exe=new File(_docRoot,first);

        while ((first.endsWith("/")||!exe.exists())&&first.length()>=0)
        {
            int index=first.lastIndexOf('/');

            first=first.substring(0,index);
            last=both.substring(index,both.length());
            exe=new File(_docRoot,first);
        }

        if (first.length()==0||!exe.exists()||exe.isDirectory()||!exe.getCanonicalPath().equals(exe.getAbsolutePath()))
        {
            res.sendError(404);
        }
        else
        {
            if (Log.isDebugEnabled())
            {
                Log.debug("CGI: script is "+exe);
                Log.debug("CGI: pathInfo is "+last);
            }
            exec(exe,last,req,res);
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @param root @param path @param req @param res @exception IOException
     */
    private void exec(File command, String pathInfo, HttpServletRequest req, HttpServletResponse res) throws IOException
    {
        String path=command.getAbsolutePath();
        File dir=command.getParentFile();
        String scriptName=req.getRequestURI().substring(0,req.getRequestURI().length()-pathInfo.length());
        String scriptPath=getServletContext().getRealPath(scriptName);
        String pathTranslated=req.getPathTranslated();

        int len=req.getContentLength();
        if (len<0)
            len=0;
        if ((pathTranslated==null)||(pathTranslated.length()==0))
            pathTranslated=path;

        EnvList env=new EnvList(_env);
        // these ones are from "The WWW Common Gateway Interface Version 1.1"
        // look at :
        // http://Web.Golux.Com/coar/cgi/draft-coar-cgi-v11-03-clean.html#6.1.1
        env.set("AUTH_TYPE",req.getAuthType());
        env.set("CONTENT_LENGTH",Integer.toString(len));
        env.set("CONTENT_TYPE",req.getContentType());
        env.set("GATEWAY_INTERFACE","CGI/1.1");
        if ((pathInfo!=null)&&(pathInfo.length()>0))
        {
            env.set("PATH_INFO",pathInfo);
        }
        env.set("PATH_TRANSLATED",pathTranslated);
        env.set("QUERY_STRING",req.getQueryString());
        env.set("REMOTE_ADDR",req.getRemoteAddr());
        env.set("REMOTE_HOST",req.getRemoteHost());
        // The identity information reported about the connection by a
        // RFC 1413 [11] request to the remote agent, if
        // available. Servers MAY choose not to support this feature, or
        // not to request the data for efficiency reasons.
        // "REMOTE_IDENT" => "NYI"
        env.set("REMOTE_USER",req.getRemoteUser());
        env.set("REQUEST_METHOD",req.getMethod());
        env.set("SCRIPT_NAME",scriptName);
        env.set("SCRIPT_FILENAME",scriptPath);
        env.set("SERVER_NAME",req.getServerName());
        env.set("SERVER_PORT",Integer.toString(req.getServerPort()));
        env.set("SERVER_PROTOCOL",req.getProtocol());
        env.set("SERVER_SOFTWARE",getServletContext().getServerInfo());

        Enumeration enm=req.getHeaderNames();
        while (enm.hasMoreElements())
        {
            String name=(String)enm.nextElement();
            String value=req.getHeader(name);
            env.set("HTTP_"+name.toUpperCase().replace('-','_'),value);
        }

        // these extra ones were from printenv on www.dev.nomura.co.uk
        env.set("HTTPS",(req.isSecure()?"ON":"OFF"));
        // "DOCUMENT_ROOT" => root + "/docs",
        // "SERVER_URL" => "NYI - http://us0245",
        // "TZ" => System.getProperty("user.timezone"),

        // are we meant to decode args here ? or does the script get them
        // via PATH_INFO ? if we are, they should be decoded and passed
        // into exec here...
        String execCmd=path;
        if ((execCmd.charAt(0)!='"')&&(execCmd.indexOf(" ")>=0))
            execCmd="\""+execCmd+"\"";
        if (_cmdPrefix!=null)
            execCmd=_cmdPrefix+" "+execCmd;

        Process p=(dir==null)?Runtime.getRuntime().exec(execCmd,env.getEnvArray()):Runtime.getRuntime().exec(execCmd,env.getEnvArray(),dir);

        // hook processes input to browser's output (async)
        final InputStream inFromReq=req.getInputStream();
        final OutputStream outToCgi=p.getOutputStream();
        final int inLength=len;

        IO.copyThread(p.getErrorStream(),System.err);
        
        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    if (inLength>0)
                        IO.copy(inFromReq,outToCgi,inLength);
                    outToCgi.close();
                }
                catch (IOException e)
                {
                    Log.ignore(e);
                }
            }
        }).start();

        // hook processes output to browser's input (sync)
        // if browser closes stream, we should detect it and kill process...
        OutputStream os = null;
        try
        {
            // read any headers off the top of our input stream
            // NOTE: Multiline header items not supported!
            String line=null;
            InputStream inFromCgi=p.getInputStream();

            //br=new BufferedReader(new InputStreamReader(inFromCgi));
            //while ((line=br.readLine())!=null)
            while( (line = getTextLineFromStream( inFromCgi )).length() > 0 )
            {
                if (!line.startsWith("HTTP"))
                {
                    int k=line.indexOf(':');
                    if (k>0)
                    {
                        String key=line.substring(0,k).trim();
                        String value = line.substring(k+1).trim();
                        if ("Location".equals(key))
                        {
                            res.sendRedirect(value);
                        }
                        else if ("Status".equals(key))
                        {
                        	String[] token = value.split( " " );
                            int status=Integer.parseInt(token[0]);
                            res.setStatus(status);
                        }
                        else
                        {
                            // add remaining header items to our response header
                            res.addHeader(key,value);
                        }
                    }
                }
            }
            // copy cgi content to response stream...
            os = res.getOutputStream();
            IO.copy(inFromCgi, os);
            p.waitFor();

            if (!_ignoreExitState)
            {
                int exitValue=p.exitValue();
                if (0!=exitValue)
                {
                    Log.warn("Non-zero exit status ("+exitValue+") from CGI program: "+path);
                    if (!res.isCommitted())
                        res.sendError(500,"Failed to exec CGI");
                }
            }
        }
        catch (IOException e)
        {
            // browser has probably closed its input stream - we
            // terminate and clean up...
            Log.debug("CGI: Client closed connection!");
        }
        catch (InterruptedException ie)
        {
            Log.debug("CGI: interrupted!");
        }
        finally
        {
            if( os != null )
            {
                try
                {
                    os.close();
                }
                catch(Exception e)
                {
                    Log.ignore(e);
                }
            }
            os = null;
            p.destroy();
            // Log.debug("CGI: terminated!");
        }
    }

    /**
     * Utility method to get a line of text from the input stream.
     * @param is the input stream
     * @return the line of text
     * @throws IOException
     */
    private String getTextLineFromStream( InputStream is ) throws IOException {
        StringBuilder buffer = new StringBuilder();
        int b;

       	while( (b = is.read()) != -1 && b != (int) '\n' ) {
       		buffer.append( (char) b );
       	}
       	return buffer.toString().trim();
    }
    /* ------------------------------------------------------------ */
    /**
     * private utility class that manages the Environment passed to exec.
     */
    private static class EnvList
    {
        private Map envMap;

        EnvList()
        {
            envMap=new HashMap();
        }

        EnvList(EnvList l)
        {
            envMap=new HashMap(l.envMap);
        }

        /**
         * Set a name/value pair, null values will be treated as an empty String
         */
        public void set(String name, String value)
        {
            envMap.put(name,name+"="+StringUtil.nonNull(value));
        }

        /** Get representation suitable for passing to exec. */
        public String[] getEnvArray()
        {
            return (String[])envMap.values().toArray(new String[envMap.size()]);
        }

        public String toString()
        {
            return envMap.toString();
        }
    }
}
