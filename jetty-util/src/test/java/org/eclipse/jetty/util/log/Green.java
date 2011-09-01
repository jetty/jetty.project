package org.eclipse.jetty.util.log;

public class Green
{
    private static final Logger LOG = Log.getLogger(Green.class);
    
    public void generateLogs() {
        LOG.debug("My color is {}", Green.class.getSimpleName());
        LOG.info("I represent the emotion Trust");
        LOG.warn("I can also mean Fear");
        LOG.ignore(new RuntimeException("Ick"));
    }
}
