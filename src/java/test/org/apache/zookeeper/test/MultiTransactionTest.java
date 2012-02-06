/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MultiTransactionTest extends ClientBase {
    private static final Logger LOG = Logger.getLogger(MultiTransactionTest.class);

    private ZooKeeper zk;

    @Before
    public void setUp() throws Exception {
        SyncRequestProcessor.setSnapCount(150);
        super.setUp();
        zk = createClient();
    }

    @Test
    public void testCreate() throws Exception {
        zk.multi(Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                ));
        zk.getData("/multi0", false, null);
        zk.getData("/multi1", false, null);
        zk.getData("/multi2", false, null);
    }

    @Test
    public void testCreateDelete() throws Exception {

        zk.multi(Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0)
                ));

        // '/multi' should have been deleted
        Assert.assertNull(zk.exists("/multi", null));
    }

    @Test
    public void testInvalidVersion() throws Exception {

        try {
            zk.multi(Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 1)
            ));
            Assert.fail("delete /multi should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }
    }

    @Test
    public void testNestedCreate() throws Exception {

        zk.multi(Arrays.asList(
                /* Create */
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a/1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),

                /* Delete */
                Op.delete("/multi/a/1", 0),
                Op.delete("/multi/a", 0),
                Op.delete("/multi", 0)
                ));

        //Verify tree deleted
        Assert.assertNull(zk.exists("/multi/a/1", null));
        Assert.assertNull(zk.exists("/multi/a", null));
        Assert.assertNull(zk.exists("/multi", null));
    }

    @Test
    public void testSetData() throws Exception {

        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            ops.add(Op.create(names[i], new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.setData(names[i], names[i].getBytes(), 0));
        }

        zk.multi(ops) ;

        for (int i = 0; i < names.length; i++) {
            Assert.assertArrayEquals(names[i].getBytes(), zk.getData(names[i], false, null));
        }
    }

    @Test
    public void testUpdateConflict() throws Exception {

        Assert.assertNull(zk.exists("/multi", null));

        try {
            zk.multi(Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.setData("/multi", "X".getBytes(), 0),
                    Op.setData("/multi", "Y".getBytes(), 0)
                    ));
            Assert.fail("Should have thrown a KeeperException for invalid version");
        } catch (KeeperException e) {
            //PASS
            LOG.error("STACKTRACE: " + e);
        }

        Assert.assertNull(zk.exists("/multi", null));

        //Updating version solves conflict -- order matters
        zk.multi(Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.setData("/multi", "X".getBytes(), 0),
                Op.setData("/multi", "Y".getBytes(), 1)
                ));

        Assert.assertArrayEquals(zk.getData("/multi", false, null), "Y".getBytes());
    }

    @Test
    public void testDeleteUpdateConflict() throws Exception {

        /* Delete of a node folowed by an update of the (now) deleted node */
        try {
            zk.multi(Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0),
                Op.setData("/multi", "Y".getBytes(), 0)
                ));
            Assert.fail("/multi should have been deleted so setData should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }

        // '/multi' should never have been created as entire op should fail
        Assert.assertNull(zk.exists("/multi", null)) ;
    }

    @Test
    public void testGetResults() throws Exception {
        /* Delete of a node folowed by an update of the (now) deleted node */
        try {
            zk.multi(Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 0),
                    Op.setData("/multi", "Y".getBytes(), 0),
                    Op.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
            ));
            Assert.fail("/multi should have been deleted so setData should have failed");
        } catch (KeeperException e) {
            // '/multi' should never have been created as entire op should fail
            Assert.assertNull(zk.exists("/multi", null));

            for (OpResult r : e.getResults()) {
                LOG.info("RESULT==> " + r);
                if (r instanceof ErrorResult) {
                    ErrorResult er = (ErrorResult) r;
                    LOG.info("ERROR RESULT: " + er + " ERR=>" + KeeperException.Code.get(er.getErr()));
                }
            }
        }
    }

    @Test
    public void testWatchesTriggered() throws KeeperException, InterruptedException {
        HasTriggeredWatcher watcher = new HasTriggeredWatcher();
        zk.getChildren("/", watcher);
        zk.multi(Arrays.asList(
                Op.create("/t", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/t", -1)
        ));
        assertTrue(watcher.triggered.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testNoWatchesTriggeredForFailedMultiRequest() throws InterruptedException, KeeperException {
        HasTriggeredWatcher watcher = new HasTriggeredWatcher();
        zk.getChildren("/", watcher);
        try {
            zk.multi(Arrays.asList(
                    Op.create("/t", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/nonexisting", -1)
            ));
            fail("expected previous multi op to fail!");
        } catch (KeeperException.NoNodeException e) {
            // expected
        }
        SyncCallback cb = new SyncCallback();
        zk.sync("/", cb, null);

        // by waiting for the callback we're assured that the event queue is flushed
        cb.done.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
        assertEquals(1, watcher.triggered.getCount());
    }

    private static class HasTriggeredWatcher implements Watcher {
        private final CountDownLatch triggered = new CountDownLatch(1);

        @Override
        public void process(WatchedEvent event) {
            triggered.countDown();
        }
    }
    private static class SyncCallback implements AsyncCallback.VoidCallback {
        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void processResult(int rc, String path, Object ctx) {
            done.countDown();
        }
    }
}