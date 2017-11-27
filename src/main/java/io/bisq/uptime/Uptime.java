package io.bisq.uptime;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
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
    private static SlackApi api;

    public static boolean ENABLE_SLACK = false;

    public static String PRICE_NODE_VERSION = "0.6.0";
    public static int LOOP_SLEEP_SECONDS = 10 * 60;
    public static int REPORTING_INTERVAL_SECONDS = 3600;
    public static int REPORTING_NR_LOOPS = REPORTING_INTERVAL_SECONDS / LOOP_SLEEP_SECONDS;

    public static int PROCESS_TIMEOUT_SECONDS = 60;

    public static List<String> clearnetBitcoinNodes = Arrays.asList(
            "138.68.117.247",
            "78.47.61.83",
            "174.138.35.229",
            "80.233.134.60",
            "192.41.136.217",
            "5.189.166.193",
            "37.221.198.57"
    );

    public static List<String> onionBitcoinNodes = Arrays.asList(
            "mxdtrjhe2yfsx3pg.onion",
            "poyvpdt762gllauu.onion",
            "r3dsojfhwcm7x7p6.onion",
            "vlf5i3grro3wux24.onion",
            "3r44ddzjitznyahw.onion",
            "i3a5xtzfm4xwtybd.onion",
            "7sl6havdhtgefwo2.onion"
    );

    public static List<String> onionPriceNodes = Arrays.asList(
            "ceaanhbvluug4we6.onion",
            "rb2l2qale2pqzjyo.onion"
    );

    public static List<String> seedNodes = Arrays.asList(
            "5quyxpxheyvzmb2d.onion:8000", // @mrosseel
            "ef5qnzx6znifo3df.onion:8000", // @alexej996
            "s67qglwhkgkyvr74.onion:8000", // @emzy
            "jhgcy2won7xnslrb.onion:8000" // @sqrrm
    );

    Set<NodeInfo> allNodes = new HashSet<>();
    HashMap<String, String> errorNodeMap = new HashMap<>();

    public void checkClearnetBitcoinNodes(List<String> ipAddresses) {
        checkBitcoinNode(ipAddresses, false);
    }

    public void checkOnionBitcoinNodes(List<String> onionAddresses) {
        checkBitcoinNode(onionAddresses, true);
    }

    public void checkPriceNodes(List<String> ipAddresses, boolean overTor) {
        NodeType nodeType = NodeType.PRICE_NODE;
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            ProcessResult getFeesResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getFees", PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(nodeType, address, getFeesResult.getError());
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handleError(nodeType, address, "Result does not contain expected keyword: " + getFeesResult.getResult());
                continue;
            }

            ProcessResult getVersionResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getVersion", PROCESS_TIMEOUT_SECONDS);
            if (getVersionResult.getError() != null) {
                handleError(nodeType, address, getVersionResult.getError());
                continue;
            }
            correct = PRICE_NODE_VERSION.equals(getVersionResult.getResult());
            if (!correct) {
                handleError(nodeType, address, "Incorrect version:" + getVersionResult.getResult());
                continue;
            }

            markAsGoodNode(nodeType, address);
        }
    }

    /**
     * NOTE: does not work on MAC netcat version
     */
    public void checkSeedNodes(List<String> addresses) {
        NodeType nodeType = NodeType.SEED_NODE;
        for (String address : addresses) {
            ProcessResult getFeesResult = executeProcess("./src/main/shell/seednodes.sh " + address, PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(nodeType, address, getFeesResult.getError());
                continue;
            }
            markAsGoodNode(nodeType, address);
        }
    }

    private void checkBitcoinNode(List<String> ipAddresses, boolean overTor) {
        NodeType nodeType = NodeType.BTC_NODE;
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            try {
                Process pr = rt.exec((overTor ? "torify " : "") + "python ./src/main/python/protocol.py " + address);
                boolean noTimeout = pr.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!noTimeout) {
                    handleError(nodeType, address, "Timeout");
                    continue;
                }
                String resultString = convertStreamToString(pr.getInputStream());
                log.info(resultString.toString());
                String[] splitResult = resultString.split(",");
                if (splitResult.length != 4) {
                    handleError(nodeType, address, "Incorrect result length:" + resultString);
                    continue;
                }
                result.setVersion(splitResult[1]);
                result.setHeight(Long.parseLong(splitResult[2]));
                result.setServices(Integer.parseInt(splitResult[3].trim()));
            } catch (IOException e) {
                handleError(nodeType, address, e.getMessage());
                continue;
            } catch (InterruptedException e) {
                handleError(nodeType, address, e.getMessage());
                continue;
            }
            markAsGoodNode(nodeType, address);
        }
    }

    private ProcessResult executeProcess(String command, int timeoutSeconds) {
        Process pr = null;
        boolean noTimeout = false;
        int exitValue = 0;
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
        exitValue = pr.exitValue();
        return new ProcessResult(convertStreamToString(pr.getInputStream()), (exitValue != 0) ? "Exit value is " + exitValue : null);
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    public void handleError(NodeType nodeType, String address, String reason) {
        log.error("Error in {} {}, reason: {}", nodeType.toString(), address, reason);
        if (!isAlreadyBadNode(address, reason)) {
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address, appendBadNodesSizeToString(reason));
        }
    }

    private void markAsGoodNode(NodeType nodeType, String address) {
        Optional<NodeInfo> any = findNodeInfoByAddress(address);
        if (any.isPresent()) {
            any.get().clearError();
            log.info("Fixed: {} {}", nodeType.getPrettyName(), address);
            SlackTool.send(api, "Fixed: " + nodeType.getPrettyName() + " " + address, appendBadNodesSizeToString("No longer in error"));
        }
    }

    private Optional<NodeInfo> findNodeInfoByAddress(String address) {
        return allNodes.stream().filter(nodeInfo -> nodeInfo.getAddress().equals(address)).findAny();
    }

    private boolean isAlreadyBadNode(String address, String reason) {
        Optional<NodeInfo> any = findNodeInfoByAddress(address);
        NodeInfo nodeInfo = any.get();
        return nodeInfo.addError(reason);
    }

    private String appendBadNodesSizeToString(String body) {
        return body + " (now " + getErrorCount() + " node(s) have errors, next check in +/-" + Math.round(LOOP_SLEEP_SECONDS / 60) + " minutes)";
    }

    public String printAllNodeStatus() {
        long errorCount = getErrorCount();
        if(errorCount == 0) {
            return "";
        }
        return "Nodes in error: " + errorCount + "\n" +
                allNodes.stream().sorted().map(nodeInfo -> padRight(nodeInfo.getNodeType().getPrettyName(), 15)
                + "|\t" + padRight(nodeInfo.getAddress(), 30)
                + "# errors: " + padRight(String.valueOf(nodeInfo.getNrErrorsSinceStart()), 5)
                + "# error minutes: " + padRight(String.valueOf(nodeInfo.getErrorMinutesSinceStart()), 6)
                + ((nodeInfo.getErrorReason().size() > 0)?" reasons: " + nodeInfo.getReasonListAsString(): ""))
                        .collect(Collectors.joining("\n"));
    }

    private long getErrorCount() {
        return allNodes.stream().filter(nodeInfo -> nodeInfo.hasError()).count();
    }

    private String padRight(String s, int padding) {
        String formatString = "%1$-" + String.valueOf(padding) + "s";
        return String.format(formatString, s);
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        final String USE_SLACK = "useSlack";
        parser.accepts(USE_SLACK, "Output is posted to a slack channel")
                .withRequiredArg().ofType(Boolean.class);
        final String SLACK_SECRET = "slackSecret";
        parser.accepts(SLACK_SECRET, "The slack secret URL")
                .withOptionalArg().ofType(String.class);

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            System.out.println("error: " + ex.getMessage());
            System.out.println();
            parser.printHelpOn(System.out);
            System.exit(-1);
            return;
        }

        //
        ENABLE_SLACK = options.has(USE_SLACK) ? (boolean) options.valueOf(USE_SLACK) : false;
        String secret = (options.has(SLACK_SECRET) && options.hasArgument(SLACK_SECRET)) ? (String) options.valueOf(SLACK_SECRET) : null;

        if (ENABLE_SLACK && secret != null) {
            log.info("Slack enabled, Using slack secret: {}", secret);
            api = new SlackApi(secret);
        } else if (ENABLE_SLACK) {
            log.info("Slack disabled due to missing slack secret");
            ENABLE_SLACK = false;
        }

        Uptime uptime = new Uptime();

        log.info("Startup. All nodes in error will be shown fully in this first run.");
        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), "Startup. All nodes in error will be shown fully in this first run.");
        int counter = 0;
        boolean isReportingLoop;

        // add all nodes to the node info list
        uptime.allNodes.addAll(onionPriceNodes.stream().map(s -> new NodeInfo(s, NodeType.PRICE_NODE)).collect(Collectors.toList()));
        uptime.allNodes.addAll(clearnetBitcoinNodes.stream().map(s -> new NodeInfo(s, NodeType.BTC_NODE)).collect(Collectors.toList()));
        uptime.allNodes.addAll(onionBitcoinNodes.stream().map(s -> new NodeInfo(s, NodeType.BTC_NODE)).collect(Collectors.toList()));
        uptime.allNodes.addAll(seedNodes.stream().map(s -> new NodeInfo(s, NodeType.SEED_NODE)).collect(Collectors.toList()));

        try {
            Thread.sleep(10000); //wait 10 seconds so that tor is started
        } catch (InterruptedException e) {
            log.error("Failed during initial sleep", e);
        }
        while (true) {
            try {
                log.info("Starting checks...");
                uptime.checkPriceNodes(onionPriceNodes, true);
                uptime.checkClearnetBitcoinNodes(clearnetBitcoinNodes);
                uptime.checkOnionBitcoinNodes(onionBitcoinNodes);
                uptime.checkSeedNodes(seedNodes);
                log.info("Stopping checks, now sleeping for {} seconds.", LOOP_SLEEP_SECONDS);

                // prepare reporting
                isReportingLoop = (counter % REPORTING_NR_LOOPS == 0);
                if (isReportingLoop) {
                    String errorNodeOutputString = uptime.printAllNodeStatus();
                    if (!errorNodeOutputString.isEmpty()) {
                        log.info("Nodes in error: \n{}", errorNodeOutputString);
                        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), errorNodeOutputString);
                    } else {
                        log.info("No errors");
                        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), "No errors");
                    }
                }
            } catch (Throwable e) {
                log.error("Could not send message to slack", e);
            }
            counter++;
            try {
                Thread.sleep(1000 * LOOP_SLEEP_SECONDS);
            } catch (InterruptedException e) {
                log.error("Error during sleep", e);
            }
        }
    }
}
