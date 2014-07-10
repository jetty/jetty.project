//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * JettyEffectiveWebXml
 *
 * @goal effective-web-xml
 * @requiresDependencyResolution test
 * @execute phase="test-compile"
 * @description Runs jetty on the unassembled webapp to generate the effective web.xml
 */
public class JettyEffectiveWebXml extends JettyRunMojo
{
    /**
     * The target directory
     * 
     * @parameter expression="${project.build.directory}"
     * @required
     * @readonly
     */
    protected File target;
    
    /**
     * The target directory
     * 
     * @parameter 
     */
    protected File effectiveWebXml;
    
    
    protected boolean deleteOnExit = true;
    

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        super.execute();
    }
    
    
    @Override
    public void startJetty() throws MojoExecutionException
    {
        //Only do enough setup to be able to produce a quickstart-web.xml file to
        //pass onto the forked process to run   
        
        //if the user didn't nominate a file to generate into, pick the name and
        //make sure that it is deleted on exit
        if (effectiveWebXml == null)
        {
            deleteOnExit = true;
            effectiveWebXml = new File(target, "effective-web.xml");
            effectiveWebXml.deleteOnExit();
        }
        
        Resource descriptor = Resource.newResource(effectiveWebXml);
        
        QueuedThreadPool tpool = null;
        
        try
        {
            printSystemProperties();

            //apply any config from a jetty.xml file first to our "fake" server instance
            //TODO probably not necessary
            applyJettyXml ();  

        
            server.configureHandlers();
                   
            //ensure config of the webapp based on settings in plugin
            configureWebApplication();
            
            
            //set the webapp up to do very little other than generate the quickstart-web.xml
            webApp.setCopyWebDir(false);
            webApp.setCopyWebInf(false);
            webApp.setGenerateQuickStart(true);
    
            if (!effectiveWebXml.getParentFile().exists())
                effectiveWebXml.getParentFile().mkdirs();
            if (!effectiveWebXml.exists())
                effectiveWebXml.createNewFile();
            
            webApp.setQuickStartWebDescriptor(descriptor);
            
            server.addWebApplication(webApp);
                       
            //if our server has a thread pool associated we can do any annotation scanning multithreaded,
            //otherwise scanning will be single threaded
            tpool = server.getBean(QueuedThreadPool.class);
            if (tpool != null)
                tpool.start();
            else
                webApp.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE.toString());
            
             webApp.start(); //just enough to generate the quickstart           
           
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Effective web.xml generation failed", e);
        }
        finally
        {
            try {webApp.stop();}catch (Exception x) {};
            
            try {if (tpool != null) tpool.stop();} catch (Exception x) {};
        }
         
       
        if (deleteOnExit)
        {
            try
            {
                //just show the result in the log
                getLog().info(IO.toString(descriptor.getInputStream()));
            }
            catch (IOException e)
            {
               throw new MojoExecutionException("Unable to output effective web.xml", e);
            }
            
        }
        
    }
}
