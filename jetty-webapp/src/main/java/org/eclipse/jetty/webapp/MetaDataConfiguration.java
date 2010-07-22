package org.eclipse.jetty.webapp;

import java.io.File;
import java.util.Enumeration;

public class MetaDataConfiguration implements Configuration
{
    final WebAppContext _template;
    
    MetaDataConfiguration(WebAppContext template)
    {
        _template=template;
    }
    
    public void preConfigure(WebAppContext context) throws Exception
    {
        File tmpDir=File.createTempFile(WebInfConfiguration.getCanonicalNameForWebAppTmpDir(context),"",_template.getTempDirectory().getParentFile());
        if (tmpDir.exists())
            tmpDir.delete();
        tmpDir.mkdir();
        tmpDir.deleteOnExit();
        context.setTempDirectory(tmpDir);
    }

    public void configure(WebAppContext context) throws Exception
    {
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
    }

    public void deconfigure(WebAppContext context) throws Exception
    {
        // TODO delete temp dir?
        // TODO other stuff from other configuration deconfigures?
    }
}
