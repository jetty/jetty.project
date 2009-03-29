package org.eclipse.jetty.continuation;

import java.io.IOException;
import java.util.ArrayList;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public class ContinuationFilter implements Filter
{
    boolean _faux;
    ServletContext _context;

    public void init(FilterConfig filterConfig) throws ServletException
    {
        _context = filterConfig.getServletContext();
        _faux=!"org.eclipse.jetty.servlet".equals(filterConfig.getClass().getPackage().getName());
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (_faux)
        {
            final FauxContinuation fc = new FauxContinuation();
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
                    _context.log("OpenServletFilter caught ",e);
                }
                catch(ServletException e)
                {
                    _context.log("OpenServletFilter caught ",e);
                }
                finally
                {
                    complete=fc.handleSuspension();
                }
            }
        }
        else
            chain.doFilter(request,response);
    }
    
    public void destroy()
    {
    }


    
    private class FauxContinuation implements Continuation
    {
        private static final int __HANDLING=1;   // Request dispatched to filter/servlet
        private static final int __SUSPENDING=2;   // Suspend called, but not yet returned to container
        private static final int __RESUMING=3;     // resumed while suspending
        private static final int __COMPLETING=4;   // resumed while suspending or suspended
        private static final int __SUSPENDED=5;    // Suspended and parked
        private static final int __UNSUSPENDING=6;
        private static final int __COMPLETE=7;
        
        private int _state=__HANDLING;
        private boolean _initial=true;
        private boolean _resumed=false;
        private boolean _timeout=false;
        private boolean _keepWrappers=false;
        
        private  long _timeoutMs=30000; // TODO configure
        
        private ArrayList<ContinuationListener> _listeners; 

        
        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.continuation.Continuation#keepWrappers()
         */
        public void keepWrappers()
        {
            _keepWrappers=true;
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.continuation.Continuation#wrappersKept()
         */
        public boolean wrappersKept()
        {
            return _keepWrappers;
        }

        /* ------------------------------------------------------------ */
        public boolean isInitial()
        {
            synchronized(this)
            {
                return _initial;
            }
        }
           
        public boolean isResumed()
        {
            synchronized(this)
            {
                return _resumed;
            }
        }
        
        public boolean isSuspended()
        {
            synchronized(this)
            {
                switch(_state)
                {
                    case __HANDLING:
                        return false;
                    case __SUSPENDING:
                    case __RESUMING:
                    case __COMPLETING:
                    case __SUSPENDED:
                        return true;
                    case __UNSUSPENDING:
                    default:
                        return false;   
                }
            }
        }

        public boolean isExpired()
        {
            synchronized(this)
            {
                return _timeout;
            }
        }

        public void setTimeout(long timeoutMs)
        {
            _timeoutMs = timeoutMs;
        }

        public void suspend()
        {
            synchronized (this)
            {
                switch(_state)
                {
                    case __HANDLING:
                        _timeout=false;
                        _resumed=false;
                        _state=__SUSPENDING;
                        return;

                    case __SUSPENDING:
                    case __RESUMING:
                        return;

                    case __COMPLETING:
                    case __SUSPENDED:
                    case __UNSUSPENDING:
                        throw new IllegalStateException(this.getStatusString());

                    default:
                        throw new IllegalStateException(""+_state);
                }

            }
        }


        /* ------------------------------------------------------------ */
        /* (non-Javadoc)
         * @see org.mortbay.jetty.Suspendor#resume()
         */
        public void resume()
        {
            synchronized (this)
            {
                switch(_state)
                {
                    case __HANDLING:
                        _resumed=true;
                        return;
                        
                    case __SUSPENDING:
                        _resumed=true;
                        _state=__RESUMING;
                        return;

                    case __RESUMING:
                    case __COMPLETING:
                        return;
                        
                    case __SUSPENDED:
                        fauxResume();
                        _resumed=true;
                        _state=__UNSUSPENDING;
                        break;
                        
                    case __UNSUSPENDING:
                        _resumed=true;
                        return;
                        
                    default:
                        throw new IllegalStateException(this.getStatusString());
                }
            }
            
        }
        
        
        public void complete()
        {
            // just like resume, except don't set _resumed=true;
            synchronized (this)
            {
                switch(_state)
                {
                    case __HANDLING:
                        throw new IllegalStateException(this.getStatusString());
                        
                    case __SUSPENDING:
                        _state=__COMPLETING;
                        break;
                        
                    case __RESUMING:
                        break;

                    case __COMPLETING:
                        return;
                        
                    case __SUSPENDED:
                        _state=__COMPLETING;
                        fauxResume();
                        break;
                        
                    case __UNSUSPENDING:
                        return;
                        
                    default:
                        throw new IllegalStateException(this.getStatusString());
                }
            }
            
        }
        

        
        
        void handling()
        {
            synchronized (this)
            {
                _keepWrappers=false;
                switch(_state)
                {
                    case __HANDLING:
                        throw new IllegalStateException(this.getStatusString());

                    case __SUSPENDING:
                    case __RESUMING:
                        throw new IllegalStateException(this.getStatusString());

                    case __COMPLETING:
                        return;

                    case __SUSPENDED:
                        fauxResume();
                    case __UNSUSPENDING:
                        _state=__HANDLING;
                        return;

                    default:
                        throw new IllegalStateException(""+_state);
                }

            }
        }

        /* ------------------------------------------------------------ */
        /**
         * @return true if handling is complete
         */
        public boolean handleSuspension()
        {
            synchronized (this)
            {
                switch(_state)
                {
                    case __HANDLING:
                        _state=__COMPLETE;
                        return true;

                    case __SUSPENDING:
                        _initial=false;
                        _state=__SUSPENDED;
                        fauxSuspend(); // could block and change state.
                        if (_state==__SUSPENDED || _state==__COMPLETING)
                            return true;
                        
                        _initial=false;
                        _state=__HANDLING;
                        return false; 

                    case __RESUMING:
                        _initial=false;
                        _state=__HANDLING;
                        return false; 

                    case __COMPLETING:
                        _initial=false;
                        _state=__COMPLETE;
                        return true;

                    case __SUSPENDED:
                    case __UNSUSPENDING:
                    default:
                        throw new IllegalStateException(this.getStatusString());
                }
            }
        }

        /* ------------------------------------------------------------ */
        protected void expire()
        {
            // just like resume, except don't set _resumed=true;

            synchronized (this)
            {
                switch(_state)
                {
                    case __HANDLING:
                        return;
                        
                    case __SUSPENDING:
                        _timeout=true;
                        _state=__RESUMING;
                        fauxResume();
                        return;
                        
                    case __RESUMING:
                        return;
                        
                    case __COMPLETING:
                        return;
                        
                    case __SUSPENDED:
                        _timeout=true;
                        _state=__UNSUSPENDING;
                        break;
                        
                    case __UNSUSPENDING:
                        _timeout=true;
                        return;
                        
                    default:
                        throw new IllegalStateException(this.getStatusString());
                }
            }
        }

        private void fauxSuspend()
        {
            long expire_at = System.currentTimeMillis()+_timeoutMs;
            long wait=_timeoutMs;
            while (_timeoutMs>0 && wait>0)
            {
                try
                {
                    this.wait(wait);
                }
                catch (InterruptedException e)
                {
                    _context.log("OpenServletFilter caught ",e);
                }
                wait=expire_at-System.currentTimeMillis();
            }

            if (_timeoutMs>0 && wait<=0)
                expire();
        }
        
        private void fauxResume()
        {
            _timeoutMs=0;
            this.notifyAll();
        }
        
        public String toString()
        {
            return getStatusString();
        }
        
        String getStatusString()
        {
            synchronized (this)
            {
                return
                ((_state==__HANDLING)?"HANDLING":
                        (_state==__SUSPENDING)?"SUSPENDING":
                            (_state==__SUSPENDED)?"SUSPENDED":
                                (_state==__RESUMING)?"RESUMING":
                                    (_state==__UNSUSPENDING)?"UNSUSPENDING":
                                        (_state==__COMPLETING)?"COMPLETING":
                                        ("???"+_state))+
                (_initial?",initial":"")+
                (_resumed?",resumed":"")+
                (_timeout?",timeout":"");
            }
        }

        
        public void addContinuationListener(ContinuationListener listener)
        {
            if (_listeners==null)
                _listeners=new ArrayList<ContinuationListener>();
            _listeners.add(listener);
            
            // TODO Call the listeners
        }
        
        
    }
}
