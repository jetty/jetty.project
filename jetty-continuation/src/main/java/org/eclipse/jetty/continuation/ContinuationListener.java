
package org.eclipse.jetty.continuation;

import java.io.IOException;
import java.util.EventListener;

public interface ContinuationListener extends EventListener 
{    
    public void onComplete(ContinuationEvent event) throws IOException;
    public void onTimeout(ContinuationEvent event) throws IOException;
}
