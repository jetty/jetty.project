package org.eclipse.jetty.continuation;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;



/* ------------------------------------------------------------ */
/** ContinuationFilter
 * <p>
 * This filter may be applied to webapplication that use the asynchronous 
 * features of the {@link Continuation} API, but that are deployed in container 
 * that is neither Jetty (>=6.1) nor a Servlet3.0 container.
 * The following init parameters may be used to configure the filter (these are mostly for testing):<dl>
 * <dt>debug</dt><dd>Boolean controlling debug output</dd>
 * <dt>partial</dt><dd>Boolean to force support for partial continuation implementations (eg jetty 6)</dd>
 * <dt>faux</dt><dd>Boolean to force support for faux continuations</dd>
 * </dl>
 */
public class ContinuationFilter implements Filter
{
    private boolean _faux;
    private boolean _partial;
    ServletContext _context;
    private boolean _debug;

    public void init(FilterConfig filterConfig) throws ServletException
    {
        boolean jetty="org.eclipse.jetty.servlet".equals(filterConfig.getClass().getPackage().getName());
        _context = filterConfig.getServletContext();
        
        String param=filterConfig.getInitParameter("debug");
        _debug=param!=null&&Boolean.parseBoolean(param);
        
        param=filterConfig.getInitParameter("partial");
        _partial=param!=null&&Boolean.parseBoolean(param) || ContinuationSupport.__jetty6;

        param=filterConfig.getInitParameter("faux");
        _faux=(param!=null&&Boolean.parseBoolean(param)) || !(jetty || _partial || ContinuationSupport.__servlet3);
        
        if (_debug)
            _context.log("ContinuationFilter partial="+_partial+
                    " jetty6="+ContinuationSupport.__jetty6+
                    " faux="+_faux+
                    " servlet3="+ContinuationSupport.__servlet3);
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (_faux)
        {
            final FauxContinuation fc = new FauxContinuation(this, request,response);
            request.setAttribute(Continuation.ATTRIBUTE,fc);
            boolean complete=false;
      
            while (!complete)
            {
                try
                {
                    chain.doFilter(request,response);
                }
                catch(IOException e)
                {
                    if (fc.isSuspended())
                        debug("ContinuationFilter caught ",e);
                    else
                        throw e;
                }
                catch(ServletException e)
                {
                    if (fc.isSuspended())
                        debug("ContinuationFilter caught ",e);
                    else
                        throw e;
                }
                finally
                {
                    complete=fc.handleSuspension();
                }
            }
            fc.onComplete();
        }
        else if (_partial)
        {
            PartialContinuation c = (PartialContinuation) request.getAttribute(Continuation.ATTRIBUTE);
            
            try
            {
                if (c==null || c.enter())
                    chain.doFilter(request,response);
            }
            finally
            {
                if (c==null)
                    c = (PartialContinuation) request.getAttribute(Continuation.ATTRIBUTE);
                if (c!=null)
                    c.exit();
            }
        }
        else
            chain.doFilter(request,response);
    }
    
    private void debug(String string, Exception e)
    {
        if (_debug)
            _context.log("DEBUG",e);
    }

    public void destroy()
    {
    }

    public interface PartialContinuation extends Continuation
    {
        boolean enter();
        void exit();
    }
}
