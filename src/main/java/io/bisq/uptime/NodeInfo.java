package io.bisq.uptime;

import lombok.AllArgsConstructor;
import lombok.Data;

/*

 */
@Data
@AllArgsConstructor
public class NodeInfo implements Comparable<NodeInfo> {
    String address;
    NodeType nodeType;
    String errorReason;

    @Override
    public int compareTo(NodeInfo o) {
        return getNodeType().compareTo(o.getNodeType());
    }
}

enum NodeType {
    PRICE_NODE, SEED_NODE, BITCOIN_NODE
}
