package io.bisq.uptime;

import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*

 */
@Slf4j
public class Uptime {
    private Runtime rt = Runtime.getRuntime();
    private static SlackApi api = new SlackApi("https://hooks.slack.com/services/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");

    public static String MONITORING_NODE = "Monitoring Node";
    public static boolean DEBUG = true;
    public static String PRICE_NODE = "Price Node";
    public static String SEED_NODE = "Seed Node";
    public static String BTC_NODE = "Bitcoin Node";

    public static String PRICE_NODE_VERSION = "0.6.0";
    public static int LOOP_SLEEP_SECONDS = 10 * 60;
    public static int REPORTING_INTERVAL_SECONDS = 3600;
    public static int REPORTING_NR_LOOPS = REPORTING_INTERVAL_SECONDS / LOOP_SLEEP_SECONDS;

    public static int PROCESS_TIMEOUT_SECONDS = 60;

    public static List<String> clearnetBitcoinNodes = Arrays.asList(
            "138.68.117.247",
            "78.47.61.83",
            "174.138.35.229",
            "192.41.136.217",
            "5.189.166.193",
            "37.221.198.57"
    );

    public static List<String> onionBitcoinNodes = Arrays.asList(
            "mxdtrjhe2yfsx3pg.onion",
            "poyvpdt762gllauu.onion",
            "r3dsojfhwcm7x7p6.onion",
            "vlf5i3grro3wux24.onion",
            "3r44ddzjitznyahw.onion"
    );

    public static List<String> onionPriceNodes = Arrays.asList(
            "ceaanhbvluug4we6.onion",
            "rb2l2qale2pqzjyo.onion"
    );

    public static List<String> seedNodes = Arrays.asList(
        //    "5quyxpxheyvzmb2d.onion:8000", // @mrosseel
         //   "ef5qnzx6znifo3df.onion:8000", // @alexej996
         //   "s67qglwhkgkyvr74.onion:8000", // @emzy
            "jhgcy2won7xnslrb.onion:8000", // @sqrrm
            "jhgcy2won7xnslr.onion:8000" // @sqrrm ERROR
    );

    Set<NodeInfo> errorNodes = new HashSet<>();
    HashMap<String, String> errorNodeMap = new HashMap<>();

    public void checkClearnetBitcoinNodes(List<String> ipAddresses) {
        checkBitcoinNode(ipAddresses, false);
    }

    public void checkOnionBitcoinNodes(List<String> onionAddresses) {
        checkBitcoinNode(onionAddresses, true);
    }

    public void checkPriceNodes(List<String> ipAddresses, boolean overTor) {
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            ProcessResult getFeesResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getFees", PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(NodeType.PRICE_NODE, address, getFeesResult.getError());
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handleError(NodeType.PRICE_NODE, address, "Result does not contain expected keyword: " + getFeesResult.getResult());
                continue;
            }

            ProcessResult getVersionResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getVersion", PROCESS_TIMEOUT_SECONDS);
            if (getVersionResult.getError() != null) {
                handleError(NodeType.PRICE_NODE, address, getVersionResult.getError());
                continue;
            }
            correct = PRICE_NODE_VERSION.equals(getVersionResult.getResult());
            if (!correct) {
                handleError(NodeType.PRICE_NODE, address, "Incorrect version:" + getVersionResult.getResult());
                continue;
            }

            markAsGoodNode(address);
        }
    }

    /** NOTE: does not work on MAC netcat version */
    public void checkSeedNodes(List<String> addresses) {
        for (String address : addresses) {
            ProcessResult getFeesResult = executeProcess("./src/main/shell/seednodes.sh " + address, PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(NodeType.SEED_NODE, address, getFeesResult.getError());
                continue;
            }
            markAsGoodNode(address);
        }
    }

    private void checkBitcoinNode(List<String> ipAddresses, boolean overTor) {
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            try {
                Process pr = rt.exec("pipenv run " + (overTor ? "torify " : "") + "python ./src/main/python/protocol.py " + address);
                boolean noTimeout = pr.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!noTimeout) {
                    handleError(NodeType.BITCOIN_NODE, address, "Timeout");
                    continue;
                }
                String resultString = convertStreamToString(pr.getInputStream());
                log.info(resultString.toString());
                String[] splitResult = resultString.split(",");
                if (splitResult.length != 4) {
                    handleError(NodeType.BITCOIN_NODE, address, "Incorrect result length:" + resultString);
                    continue;
                }
                result.setVersion(splitResult[1]);
                result.setHeight(Long.parseLong(splitResult[2]));
                result.setServices(Integer.parseInt(splitResult[3].trim()));
            } catch (IOException e) {
                handleError(NodeType.BITCOIN_NODE, address, e.getMessage());
                continue;
            } catch (InterruptedException e) {
                handleError(NodeType.BITCOIN_NODE, address, e.getMessage());
                continue;
            }
            markAsGoodNode(address);
        }
    }

    private ProcessResult executeProcess(String command, int timeoutSeconds) {
        Process pr = null;
        boolean noTimeout = false;
        try {
            pr = rt.exec(command);
            noTimeout = pr.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (IOException e) {
            return new ProcessResult("", e.getMessage());
        } catch (InterruptedException e) {
            return new ProcessResult("", e.getMessage());
        }
        if (!noTimeout) {
            return new ProcessResult(null, "Timeout");
        }
        return new ProcessResult(convertStreamToString(pr.getInputStream()), null);
    }

    public void handleError(NodeType nodeType, String address, String reason) {
        log.error("Error in {} {}, reason: {}", nodeType.toString(), address, reason);
        if (!isAlreadyBadNode(address, nodeType, reason)) {
            SlackTool.send(api, nodeType.toString() + " " + address, appendBadNodesSizeToString(reason));
        }
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void markAsGoodNode(String address) {
        Optional<NodeInfo> any = errorNodes.stream().filter(nodeInfo -> nodeInfo.getAddress().equals(address)).findAny();
        if (any.isPresent()) {
            boolean removed = errorNodes.remove(any.get());
            SlackTool.send(api, address, appendBadNodesSizeToString("No longer in error"));
        }
    }

    private boolean isAlreadyBadNode(String address, NodeType nodeType, String reason) {
        return !errorNodes.add(new NodeInfo(address, nodeType, reason));
    }

    private String appendBadNodesSizeToString(String body) {
        return body + " (now " + errorNodes.size() + " node(s) have errors, next check in +/-" + Math.round(LOOP_SLEEP_SECONDS/60) + " minutes)";
    }

    public String printErrorNodes() {
        return errorNodes.stream().sorted().map(nodeInfo -> nodeInfo.getNodeType().toString() + "\t|\t" + nodeInfo.getAddress() + "\t" + nodeInfo.getErrorReason()).collect(Collectors.joining("\n"));
    }

    public static void main(String[] args) {
        Uptime uptime = new Uptime();
        log.info("Startup. All nodes in error will be shown fully in this first run.");
        SlackTool.send(api, MONITORING_NODE, "Startup. All nodes in error will be shown fully in this first run.");
        int counter = 0;
        boolean isReportingLoop;
        while (true) {
            try {
                log.info("Starting checks...");
                //uptime.checkPriceNodes(onionPriceNodes, true);
                //uptime.checkClearnetBitcoinNodes(clearnetBitcoinNodes);
                //uptime.checkOnionBitcoinNodes(onionBitcoinNodes);
                uptime.checkSeedNodes(seedNodes);
                log.info("Stopping checks, now sleeping for {} seconds.", LOOP_SLEEP_SECONDS);

                // prepare reporting
                isReportingLoop = (counter % REPORTING_NR_LOOPS == 0);
                if (isReportingLoop) {
                    String errorNodeOutputString = uptime.printErrorNodes();
                    if (!errorNodeOutputString.isEmpty()) {
                        log.info("Nodes in error: \n{}", errorNodeOutputString);
                        SlackTool.send(api, MONITORING_NODE, "Nodes in error: \n" + errorNodeOutputString);
                    } else {
                        log.info("No errors");
                        SlackTool.send(api, MONITORING_NODE, "No errors");
                    }
                }

                try {
                    Thread.sleep(1000 * LOOP_SLEEP_SECONDS);
                } catch (InterruptedException e) {
                    log.error("Error during sleep", e);
                }
                counter++;
            } catch (Throwable e) {
                log.error("Could not send message to slack", e);
            }
        }
    }
}
