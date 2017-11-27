package io.bisq.uptime;

/*

 */

import org.junit.Test;

import static org.junit.Assert.*;

public class NodeInfoTest {
    @Test
    public void addError() throws Exception {
        NodeInfo nodeInfo = new NodeInfo("123address", NodeType.SEED_NODE);

        assertFalse(nodeInfo.hasError());
        assertEquals(0, nodeInfo.getErrorMinutesSinceStart());
        assertFalse(nodeInfo.getLastErrorTime().isPresent());
        assertEquals("123address", nodeInfo.getAddress());
        assertNotNull(nodeInfo.getStartTime());
        assertEquals(0, nodeInfo.getNrErrorsSinceStart());


        nodeInfo.addError("BlaError");
        assertTrue(nodeInfo.hasError());
        assertTrue(nodeInfo.getLastErrorTime().isPresent());
        assertTrue(nodeInfo.getReasonListAsString().length() > 0);
        assertEquals(1, nodeInfo.getNrErrorsSinceStart());

        nodeInfo.clearError();
        assertFalse(nodeInfo.hasError());
        assertFalse(nodeInfo.getLastErrorTime().isPresent());
        assertTrue(nodeInfo.getReasonListAsString().length() == 0);
        assertEquals(1, nodeInfo.getNrErrorsSinceStart());

        // clearing a second time gives no errors
        nodeInfo.clearError();

        // Error counter increases
        nodeInfo.addError("BlaError");
        assertEquals(2, nodeInfo.getNrErrorsSinceStart());

        // Error counter increases, also if already in error
        nodeInfo.addError("BlaError");
        assertEquals(3, nodeInfo.getNrErrorsSinceStart());
    }
}
