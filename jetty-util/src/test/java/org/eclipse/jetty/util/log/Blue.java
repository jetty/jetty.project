package org.eclipse.jetty.util.log;

public class Blue
{
    private static final Logger LOG = Log.getLogger(Blue.class);
    
    public void generateLogs() {
        LOG.debug("My color is {}", Blue.class.getSimpleName());
        LOG.info("I represent the emotion Admiration");
        LOG.warn("I can also mean Disgust");
        LOG.ignore(new RuntimeException("Yawn"));
    }
}
