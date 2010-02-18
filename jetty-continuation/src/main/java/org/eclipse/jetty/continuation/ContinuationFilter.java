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
 * <dt>jetty6</dt><dd>Boolean to force support for jetty 6 continuations)</dd>
 * <dt>faux</dt><dd>Boolean to force support for faux continuations</dd>
 * </dl>
 */
public class ContinuationFilter implements Filter
{
    static boolean _initialized;
    static boolean __debug; // shared debug status
    private boolean _faux;
    private boolean _jetty6;
    private boolean _filtered;
    ServletContext _context;
    private boolean _debug;

    public void init(FilterConfig filterConfig) throws ServletException
    {
        boolean jetty_7_or_greater="org.eclipse.jetty.servlet".equals(filterConfig.getClass().getPackage().getName());
        _context = filterConfig.getServletContext();
        
        String param=filterConfig.getInitParameter("debug");
        _debug=param!=null&&Boolean.parseBoolean(param);
        if (_debug)
            __debug=true;
        
        param=filterConfig.getInitParameter("jetty6");
        if (param==null)
            param=filterConfig.getInitParameter("partial");
        if (param!=null)
            _jetty6=Boolean.parseBoolean(param);
        else
            _jetty6=ContinuationSupport.__jetty6 && !jetty_7_or_greater;

        param=filterConfig.getInitParameter("faux");
        if (param!=null)
            _faux=Boolean.parseBoolean(param);
        else
            _faux=!(jetty_7_or_greater || _jetty6 || _context.getMajorVersion()>=3);
        
        _filtered=_faux||_jetty6;
        if (_debug)
            _context.log("ContinuationFilter "+
                    " jetty="+jetty_7_or_greater+
                    " jetty6="+_jetty6+
                    " faux="+_faux+
                    " filtered="+_filtered+
                    " servlet3="+ContinuationSupport.__servlet3);
        _initialized=true;
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (_filtered)
        {

            Continuation c = (Continuation) request.getAttribute(Continuation.ATTRIBUTE);
            FilteredContinuation fc;
            if (_faux && (c==null || !(c instanceof FauxContinuation)))
            {
                fc = new FauxContinuation(request);
                request.setAttribute(Continuation.ATTRIBUTE,fc);
            }
            else
                fc=(FilteredContinuation)c;

            boolean complete=false;
            while (!complete)
            {
                try
                {
                    if (fc==null || (fc).enter(response))
                        chain.doFilter(request,response);
                }
                catch (ContinuationThrowable e)
                {
                    debug("faux",e);
                }
                finally
                {
                    if (fc==null)
                        fc = (FilteredContinuation) request.getAttribute(Continuation.ATTRIBUTE);

                    complete=fc==null || (fc).exit();
                }
            }
        }
        else
        {
            try
            {
                chain.doFilter(request,response);
            }
            catch (ContinuationThrowable e)
            {
                debug("caught",e);
            }
        }
    }

    private void debug(String string)
    {
        if (_debug)
        {
            _context.log(string);
        }
    }
    
    private void debug(String string, Throwable th)
    {
        if (_debug)
        {
            if (th instanceof ContinuationThrowable)
                _context.log(string+":"+th);
            else
                _context.log(string,th);
        }
    }

    public void destroy()
    {
    }

    public interface FilteredContinuation extends Continuation
    {
        boolean enter(ServletResponse response);
        boolean exit();
    }
}
