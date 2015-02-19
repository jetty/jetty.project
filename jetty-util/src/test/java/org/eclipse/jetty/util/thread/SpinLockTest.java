/* ------------------------------------------------------------ */
/** Spin Lock
 * <p>This is a lock designed to protect VERY short sections of 
 * critical code.  Threads attempting to take the lock will spin 
 * forever until the lock is available, thus it is important that
 * the code protected by this lock is extremely simple and non
 * blocking. The reason for this lock is that it prevents a thread
 * from giving up a CPU core when contending for the lock.</p>
 * <pre>
 * try(SpinLock.Lock lock = spinlock.lock())
 * {
 *   // something very quick and non blocking
 * }
 * </pre>
 */

package org.eclipse.jetty.util.thread;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class SpinLockTest
{
    
    @Test
    public void testLocked()
    {
        SpinLock lock = new SpinLock();
        assertFalse(lock.isLocked());
        
        try(SpinLock.Lock l = lock.lock())
        {
            assertTrue(lock.isLocked());
        }
        finally
        {
            assertFalse(lock.isLocked());
        }

        assertFalse(lock.isLocked());
    }
    
    @Test
    public void testLockedException()
    {
        SpinLock lock = new SpinLock();
        assertFalse(lock.isLocked());
        
        try(SpinLock.Lock l = lock.lock())
        {
            assertTrue(lock.isLocked());
            throw new Exception();
        }
        catch(Exception e)
        {
            assertFalse(lock.isLocked());
        }
        finally
        {
            assertFalse(lock.isLocked());
        }

        assertFalse(lock.isLocked());
    }


    @Test
    public void testContend() throws Exception
    {
        final SpinLock lock = new SpinLock();
        
        final CountDownLatch held0 = new CountDownLatch(1);
        final CountDownLatch hold0 = new CountDownLatch(1);
        
        Thread thread0 = new Thread()
        {
            @Override
            public void run()
            {
                try(SpinLock.Lock l = lock.lock())
                {
                    held0.countDown();
                    hold0.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        };
        thread0.start();
        held0.await();

        assertTrue(lock.isLocked());
        
        
        final CountDownLatch held1 = new CountDownLatch(1);
        final CountDownLatch hold1 = new CountDownLatch(1);
        Thread thread1 = new Thread()
        {
            @Override
            public void run()
            {
                try(SpinLock.Lock l = lock.lock())
                {
                    held1.countDown();
                    hold1.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        };
        thread1.start();
        // thread1 will be spinning here
        assertFalse(held1.await(100,TimeUnit.MILLISECONDS));
        
        // Let thread0 complete
        hold0.countDown();
        thread0.join();
        
        // thread1 can progress
        held1.await();
        
        // let thread1 complete
        hold1.countDown();
        thread1.join();

        assertFalse(lock.isLocked());
        
    }
    
    
}
