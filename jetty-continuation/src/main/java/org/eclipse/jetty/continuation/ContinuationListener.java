
package org.eclipse.jetty.continuation;

import java.io.IOException;
import java.util.EventListener;


public interface ContinuationListener extends EventListener 
{    
    public void onComplete(Continuation continuation);
    public void onTimeout(Continuation continuation);
}
