package io.bisq.uptime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/*

 */
@Data
@AllArgsConstructor
public class NodeInfo implements Comparable<NodeInfo> {
    String address;
    NodeType nodeType;
    List<String> errorReason;

    @Override
    public int compareTo(NodeInfo o) {
        return getNodeType().compareTo(o.getNodeType());
    }

    public String getReasonListAsString() {
        return errorReason.stream().collect(Collectors.joining("\n"));
    }
}

enum NodeType {
    PRICE_NODE("Price node"), SEED_NODE("Seed node"), BITCOIN_NODE("Bitcoin node"), MONITORING_NODE("Monitoring node");

    @Getter
    private final String prettyName;

    NodeType(String prettyName) {
        this.prettyName = prettyName;
    }

}
