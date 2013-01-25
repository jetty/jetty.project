package org.eclipse.jetty.server.session;

import java.io.File;

import junit.framework.Assert;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Test;

public class HashSessionManagerTest
{

    @Test
    public void testDangerousSessionId() throws Exception
    {
        final HashSessionManager manager = new HashSessionManager();
        manager.setDeleteUnrestorableSessions(true);
        manager.setLazyLoad(true);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        testDir.mkdirs();
        manager.setStoreDirectory(testDir);
        
        MavenTestingUtils.getTargetFile("dangerFile.session").createNewFile();
        
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile("dangerFile.session").exists());
        
        manager.getSession("../../dangerFile.session");
        
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile("dangerFile.session").exists());

    }
}
