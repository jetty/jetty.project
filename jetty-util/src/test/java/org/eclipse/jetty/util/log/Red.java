package org.eclipse.jetty.util.log;

public class Red
{
    private static final Logger LOG = Log.getLogger(Red.class);
    
    public void generateLogs() {
        LOG.debug("My color is {}", Red.class.getSimpleName());
        LOG.info("I represent the emotion Love");
        LOG.warn("I can also mean Anger");
        LOG.ignore(new RuntimeException("Nom"));
    }
}
