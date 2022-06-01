//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CGI Servlet.
 * <p>
 * The following init parameters are used to configure this servlet:
 * <dl>
 * <dt>cgibinResourceBase</dt>
 * <dd>Path to the cgi bin directory if set or it will default to the resource base of the context.</dd>
 * <dt>resourceBase</dt>
 * <dd>An alias for cgibinResourceBase.</dd>
 * <dt>cgibinResourceBaseIsRelative</dt>
 * <dd>If true then cgibinResourceBase is relative to the webapp (eg "WEB-INF/cgi")</dd>
 * <dt>commandPrefix</dt>
 * <dd>may be used to set a prefix to all commands passed to exec. This can be used on systems that need assistance to execute a
 * particular file type. For example on windows this can be set to "perl" so that perl scripts are executed.</dd>
 * <dt>Path</dt>
 * <dd>passed to the exec environment as PATH.</dd>
 * <dt>ENV_*</dt>
 * <dd>used to set an arbitrary environment variable with the name stripped of the leading ENV_ and using the init parameter value</dd>
 * <dt>useFullPath</dt>
 * <dd>If true, the full URI path within the context is used for the exec command, otherwise a search is done for a partial URL that matches an exec Command</dd>
 * <dt>ignoreExitState</dt>
 * <dd>If true then do not act on a non-zero exec exit status")</dd>
 * </dl>
 */
public class CGI extends HttpServlet
{
    private static final long serialVersionUID = -6182088932884791074L;

    private static final Logger LOG = LoggerFactory.getLogger(CGI.class);

    private boolean _ok;
    private File _docRoot;
    private boolean _cgiBinProvided;
    private String _path;
    private String _cmdPrefix;
    private boolean _useFullPath;
    private EnvList _env;
    private boolean _ignoreExitState;
    private boolean _relative;

    @Override
    public void init() throws ServletException
    {
        _env = new EnvList();
        _cmdPrefix = getInitParameter("commandPrefix");
        _useFullPath = Boolean.parseBoolean(getInitParameter("useFullPath"));
        _relative = Boolean.parseBoolean(getInitParameter("cgibinResourceBaseIsRelative"));

        String tmp = getInitParameter("cgibinResourceBase");
        if (tmp != null)
            _cgiBinProvided = true;
        else
        {
            tmp = getInitParameter("resourceBase");
            if (tmp != null)
                _cgiBinProvided = true;
            else
                tmp = getServletContext().getRealPath("/");
        }

        if (_relative && _cgiBinProvided)
        {
            tmp = getServletContext().getRealPath(tmp);
        }

        if (tmp == null)
        {
            LOG.warn("CGI: no CGI bin !");
            return;
        }

        File dir = new File(tmp);
        if (!dir.exists())
        {
            LOG.warn("CGI: CGI bin does not exist - {}", dir);
            return;
        }

        if (!dir.canRead())
        {
            LOG.warn("CGI: CGI bin is not readable - {}", dir);
            return;
        }

        if (!dir.isDirectory())
        {
            LOG.warn("CGI: CGI bin is not a directory - {}", dir);
            return;
        }

        try
        {
            _docRoot = dir.getCanonicalFile();
        }
        catch (IOException e)
        {
            LOG.warn("CGI: CGI bin failed - {}", dir, e);
            return;
        }

        _path = getInitParameter("Path");
        if (_path != null)
            _env.set("PATH", _path);

        _ignoreExitState = "true".equalsIgnoreCase(getInitParameter("ignoreExitState"));
        Enumeration<String> e = getInitParameterNames();
        while (e.hasMoreElements())
        {
            String n = e.nextElement();
            if (n != null && n.startsWith("ENV_"))
                _env.set(n.substring(4), getInitParameter(n));
        }
        if (!_env.envMap.containsKey("SystemRoot"))
        {
            String os = System.getProperty("os.name");
            if (os != null && os.toLowerCase(Locale.ENGLISH).contains("windows"))
            {
                _env.set("SystemRoot", "C:\\WINDOWS");
            }
        }

        _ok = true;
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
    {
        if (!_ok)
        {
            res.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("CGI: ContextPath : {}, ServletPath : {}, PathInfo : {}, _docRoot : {}, _path : {}, _ignoreExitState : {}",
                req.getContextPath(), req.getServletPath(), req.getPathInfo(), _docRoot, _path, _ignoreExitState);
        }

        // pathInContext may actually comprises scriptName/pathInfo...We will
        // walk backwards up it until we find the script - the rest must
        // be the pathInfo;
        String pathInContext = (_relative ? "" : StringUtil.nonNull(req.getServletPath())) + StringUtil.nonNull(req.getPathInfo());
        File execCmd = new File(_docRoot, pathInContext);
        String pathInfo = pathInContext;

        if (!_useFullPath)
        {
            String path = pathInContext;
            String info = "";

            // Search docroot for a matching execCmd
            while ((path.endsWith("/") || !execCmd.exists()) && path.length() >= 0)
            {
                int index = path.lastIndexOf('/');
                path = path.substring(0, index);
                info = pathInContext.substring(index, pathInContext.length());
                execCmd = new File(_docRoot, path);
            }

            if (path.length() == 0 || !execCmd.exists() || execCmd.isDirectory() || !execCmd.getCanonicalPath().equals(execCmd.getAbsolutePath()))
            {
                res.sendError(404);
            }

            pathInfo = info;
        }
        exec(execCmd, pathInfo, req, res);
    }

    /**
     * executes the CGI process
     *
     * @param command the command to execute, this command is prefixed by
     * the context parameter "commandPrefix".
     * @param pathInfo The PATH_INFO to process. Cannot be null
     * @param req the HTTP request
     * @param res the HTTP response
     * @throws IOException if the execution of the CGI process throws
     */
    private void exec(File command, String pathInfo, HttpServletRequest req, HttpServletResponse res) throws IOException
    {
        assert req != null;
        assert res != null;
        assert pathInfo != null;
        assert command != null;

        if (LOG.isDebugEnabled())
        {
            LOG.debug("CGI: script is {} pathInfo is {}", command, pathInfo);
        }

        String bodyFormEncoded = null;
        if ((HttpMethod.POST.is(req.getMethod()) || HttpMethod.PUT.is(req.getMethod())) && "application/x-www-form-urlencoded".equals(req.getContentType()))
        {
            MultiMap<String> parameterMap = new MultiMap<>();
            Enumeration<String> names = req.getParameterNames();
            while (names.hasMoreElements())
            {
                String parameterName = names.nextElement();
                parameterMap.addValues(parameterName, req.getParameterValues(parameterName));
            }

            String characterEncoding = req.getCharacterEncoding();
            Charset charset = characterEncoding != null
                ? Charset.forName(characterEncoding) : StandardCharsets.UTF_8;
            bodyFormEncoded = UrlEncoded.encode(parameterMap, charset, true);
        }

        EnvList env = new EnvList(_env);
        // these ones are from "The WWW Common Gateway Interface Version 1.1"
        // look at :
        // http://Web.Golux.Com/coar/cgi/draft-coar-cgi-v11-03-clean.html#6.1.1
        env.set("AUTH_TYPE", req.getAuthType());

        int contentLen = req.getContentLength();
        if (contentLen < 0)
            contentLen = 0;
        if (bodyFormEncoded != null)
        {
            env.set("CONTENT_LENGTH", Integer.toString(bodyFormEncoded.length()));
        }
        else
        {
            env.set("CONTENT_LENGTH", Integer.toString(contentLen));
        }
        env.set("CONTENT_TYPE", req.getContentType());
        env.set("GATEWAY_INTERFACE", "CGI/1.1");
        if (pathInfo.length() > 0)
        {
            env.set("PATH_INFO", pathInfo);
        }

        String pathTranslated = req.getPathTranslated();
        if ((pathTranslated == null) || (pathTranslated.length() == 0))
            pathTranslated = pathInfo;
        env.set("PATH_TRANSLATED", pathTranslated);
        env.set("QUERY_STRING", req.getQueryString());
        env.set("REMOTE_ADDR", req.getRemoteAddr());
        env.set("REMOTE_HOST", req.getRemoteHost());

        // The identity information reported about the connection by a
        // RFC 1413 [11] request to the remote agent, if
        // available. Servers MAY choose not to support this feature, or
        // not to request the data for efficiency reasons.
        // "REMOTE_IDENT" => "NYI"
        env.set("REMOTE_USER", req.getRemoteUser());
        env.set("REQUEST_METHOD", req.getMethod());

        String scriptPath;
        String scriptName;
        // use docRoot for scriptPath, too
        if (_cgiBinProvided)
        {
            scriptPath = command.getAbsolutePath();
            scriptName = scriptPath.substring(_docRoot.getAbsolutePath().length());
        }
        else
        {
            String requestURI = req.getRequestURI();
            scriptName = requestURI.substring(0, requestURI.length() - pathInfo.length());
            scriptPath = getServletContext().getRealPath(scriptName);
        }
        env.set("SCRIPT_FILENAME", scriptPath);
        env.set("SCRIPT_NAME", scriptName);

        env.set("SERVER_NAME", req.getServerName());
        env.set("SERVER_PORT", Integer.toString(req.getServerPort()));
        env.set("SERVER_PROTOCOL", req.getProtocol());
        env.set("SERVER_SOFTWARE", getServletContext().getServerInfo());

        Enumeration<String> enm = req.getHeaderNames();
        while (enm.hasMoreElements())
        {
            String name = enm.nextElement();
            if (name.equalsIgnoreCase("Proxy"))
                continue;
            String value = req.getHeader(name);
            env.set("HTTP_" + StringUtil.replace(name.toUpperCase(Locale.ENGLISH), '-', '_'), value);
        }

        // these extra ones were from printenv on www.dev.nomura.co.uk
        env.set("HTTPS", (req.isSecure() ? "ON" : "OFF"));
        // "DOCUMENT_ROOT" => root + "/docs",
        // "SERVER_URL" => "NYI - http://us0245",
        // "TZ" => System.getProperty("user.timezone"),

        // are we meant to decode args here? or does the script get them
        // via PATH_INFO? if we are, they should be decoded and passed
        // into exec here...
        String absolutePath = command.getAbsolutePath();
        String execCmd = absolutePath;

        // escape the execCommand
        if (execCmd.length() > 0 && execCmd.charAt(0) != '"' && execCmd.contains(" "))
            execCmd = "\"" + execCmd + "\"";

        if (_cmdPrefix != null)
            execCmd = _cmdPrefix + " " + execCmd;

        if (LOG.isDebugEnabled())
            LOG.debug("Environment: {} Command: {}", env.getExportString(), execCmd);

        final Process p = Runtime.getRuntime().exec(execCmd, env.getEnvArray(), _docRoot);

        // hook processes input to browser's output (async)
        if (bodyFormEncoded != null)
            writeProcessInput(p, bodyFormEncoded);
        else if (contentLen > 0)
            writeProcessInput(p, req.getInputStream(), contentLen);

        // hook processes output to browser's input (sync)
        // if browser closes stream, we should detect it and kill process...
        OutputStream os = null;
        AsyncContext async = req.startAsync();
        try
        {
            async.start(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        IO.copy(p.getErrorStream(), System.err);
                    }
                    catch (IOException e)
                    {
                        LOG.warn("Unable to copy error stream", e);
                    }
                }
            });

            // read any headers off the top of our input stream
            // NOTE: Multiline header items not supported!
            String line = null;
            InputStream inFromCgi = p.getInputStream();

            // br=new BufferedReader(new InputStreamReader(inFromCgi));
            // while ((line=br.readLine())!=null)
            while ((line = getTextLineFromStream(inFromCgi)).length() > 0)
            {
                if (!line.startsWith("HTTP"))
                {
                    int k = line.indexOf(':');
                    if (k > 0)
                    {
                        String key = line.substring(0, k).trim();
                        String value = line.substring(k + 1).trim();
                        if ("Location".equals(key))
                        {
                            res.sendRedirect(res.encodeRedirectURL(value));
                        }
                        else if ("Status".equals(key))
                        {
                            String[] token = value.split(" ");
                            int status = Integer.parseInt(token[0]);
                            res.setStatus(status);
                        }
                        else
                        {
                            // add remaining header items to our response header
                            res.addHeader(key, value);
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
                int exitValue = p.exitValue();
                if (0 != exitValue)
                {
                    LOG.warn("Non-zero exit status ({}) from CGI program: {}", exitValue, absolutePath);
                    if (!res.isCommitted())
                        res.sendError(500, "Failed to exec CGI");
                }
            }
        }
        catch (IOException e)
        {
            // browser has probably closed its input stream - we
            // terminate and clean up...
            LOG.debug("CGI: Client closed connection!", e);
        }
        catch (InterruptedException ex)
        {
            LOG.debug("CGI: interrupted!");
        }
        finally
        {
            IO.close(os);
            p.destroy();
            // LOG.debug("CGI: terminated!");
            async.complete();
        }
    }

    private static void writeProcessInput(final Process p, final String input)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    try (Writer outToCgi = new OutputStreamWriter(p.getOutputStream()))
                    {
                        outToCgi.write(input);
                    }
                }
                catch (IOException e)
                {
                    LOG.debug("Unable to write out to CGI", e);
                }
            }
        }).start();
    }

    private static void writeProcessInput(final Process p, final InputStream input, final int len)
    {
        if (len <= 0)
            return;

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    try (OutputStream outToCgi = p.getOutputStream())
                    {
                        IO.copy(input, outToCgi, len);
                    }
                }
                catch (IOException e)
                {
                    LOG.debug("Unable to write out to CGI", e);
                }
            }
        }).start();
    }

    /**
     * Utility method to get a line of text from the input stream.
     *
     * @param is the input stream
     * @return the line of text
     * @throws IOException if reading from the input stream throws
     */
    private static String getTextLineFromStream(InputStream is) throws IOException
    {
        StringBuilder buffer = new StringBuilder();
        int b;

        while ((b = is.read()) != -1 && b != '\n')
        {
            buffer.append((char)b);
        }
        return buffer.toString().trim();
    }

    /**
     * private utility class that manages the Environment passed to exec.
     */
    private static class EnvList
    {
        private Map<String, String> envMap;

        EnvList()
        {
            envMap = new HashMap<>();
        }

        EnvList(EnvList l)
        {
            envMap = new HashMap<>(l.envMap);
        }

        /**
         * Set a name/value pair, null values will be treated as an empty String
         *
         * @param name the name
         * @param value the value
         */
        public void set(String name, String value)
        {
            envMap.put(name, name + "=" + StringUtil.nonNull(value));
        }

        /**
         * Get representation suitable for passing to exec.
         *
         * @return the env map as an array
         */
        public String[] getEnvArray()
        {
            return envMap.values().toArray(new String[envMap.size()]);
        }

        public String getExportString()
        {
            StringBuilder sb = new StringBuilder();
            for (String variable : getEnvArray())
            {
                sb.append("export \"");
                sb.append(variable);
                sb.append("\"; ");
            }
            return sb.toString();
        }

        @Override
        public String toString()
        {
            return envMap.toString();
        }
    }
}
