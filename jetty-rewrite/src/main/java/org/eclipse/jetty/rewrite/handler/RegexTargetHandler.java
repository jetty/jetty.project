package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** A handle that uses regular expressions to select the target.
 * <p>
 * This handler applies a list of regex to target name mappings to the URIs of requests. 
 * If the regex matches the URI, then the mapped target name is used in the nested
 * call to {@link #doScope(String, Request, HttpServletRequest, HttpServletResponse)}.
 * <p>
 * This handler should be installed as the first handler in a Context.   It can be configured
 * either with direct calls to {@link #addPatternTarget(String, String)} or by setting
 * the context init parameters "org.eclipse.jetty.rewrite.handler.REGEX_MAPPINGS" to a comma
 * separated list of strings in the format regex==target.
 */
public class RegexTargetHandler extends ScopedHandler
{
    private final static Logger LOG = Log.getLogger(RegexTargetHandler.class);
    public final static String REGEX_MAPPINGS="org.eclipse.jetty.rewrite.handler.REGEX_MAPPINGS";
    static class RegexMapping
    {
        RegexMapping(String regex,String target)
        {
            _pattern=Pattern.compile(regex);
            _target=target;
        }
        final Pattern _pattern;
        final String _target;
        
        public String toString()
        {
            return _pattern+"=="+_target;
        }
    }
    
    final private List<RegexTargetHandler.RegexMapping> _patterns = new CopyOnWriteArrayList<RegexTargetHandler.RegexMapping>();
    
    /* ------------------------------------------------------------ */
    /** Add a pattern to target mapping.
     * @param pattern The regular expression pattern to match.  
     * @param target The target (normally servlet name) to handle the request
     */
    public void addPatternTarget(String pattern,String target)
    {
        _patterns.add(new RegexMapping(pattern,target));
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        
        Context context = ContextHandler.getCurrentContext();
        if (context!=null)
        {
            String config=context.getInitParameter(REGEX_MAPPINGS);
            LOG.debug("{}={}",REGEX_MAPPINGS,config);
            String[] mappings=config.split("\\s*,\\s*");
            for (String mapping : mappings)
            {
                mapping=mapping.trim();
                String[] parts=mapping.split("\\s*==\\s*");
                if (parts.length==2)
                {
                    String pattern=parts[0];
                    String target=parts[1];
                    addPatternTarget(pattern,target);
                }
                else
                    LOG.warn("Bad regex mapping: "+mapping);
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        for (RegexTargetHandler.RegexMapping rm : _patterns)
        {
            Matcher m=rm._pattern.matcher(target);
            if (m.matches())
            {
                String new_target = rm._target;
                final String sp;
                final String pi;
                
                if (m.groupCount()==1&&target.endsWith(m.group(1)))
                {
                    pi=m.group(1);
                    sp=target.substring(0,target.length()-pi.length());
                }
                else
                {
                    sp=target;
                    pi=null;
                }
                baseRequest.setServletPath(sp);
                baseRequest.setPathInfo(pi);
                baseRequest.setAttribute("org.eclipse.jetty.servlet.REGEX_PATH",target);
                super.nextScope(new_target,baseRequest,request,response);
                return;
            }
        }
        super.nextScope(target,baseRequest,request,response);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        String path=(String)baseRequest.getAttribute("org.eclipse.jetty.servlet.REGEX_PATH");
        if (path==null)
            path=target;
        else
            baseRequest.setAttribute("org.eclipse.jetty.servlet.REGEX_PATH",null);
        
        super.nextHandle(path,baseRequest,request,response);
    }

    /* ------------------------------------------------------------ */
    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out,indent,_patterns,Collections.singletonList(getHandler()));
    }
    
    
}