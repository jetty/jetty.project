
package org.eclipse.jetty.continuation;

import java.util.EventListener;

import javax.servlet.ServletRequestListener;


/* ------------------------------------------------------------ */
/** A Continuation Listener
 * <p>
 * A ContinuationListener may be registered with a call to
 * {@link Continuation#addContinuationListener(ContinuationListener)}.
 * 
 */
public interface ContinuationListener extends EventListener 
{    
    /* ------------------------------------------------------------ */
    /**
     * Called when a continuation life cycle is complete and after
     * any calls to {@link ServletRequestListener#requestDestroyed(javax.servlet.ServletRequestEvent)}
     * The response may still be written to during the call.
     * 
     * @param continuation
     */
    public void onComplete(Continuation continuation);
    
    /* ------------------------------------------------------------ */
    /**
     * Called when a suspended continuation has timed out.
     * The response may be written to and the methods 
     * {@link Continuation#resume()} or {@link Continuation#complete()} 
     * may be called by a onTimeout implementation,
     * @param continuation
     */
    public void onTimeout(Continuation continuation);

}
