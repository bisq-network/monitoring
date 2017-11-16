package io.bisq.uptime;

import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/*

 */
@Slf4j
public class Uptime {
    private Runtime rt = Runtime.getRuntime();
    private static SlackApi api = new SlackApi("https://hooks.slack.com/services/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");

    public static String PRICE_NODE_VERSION = "0.6.0";
    public static int LOOP_SLEEP_SECONDS = 60;
    public static int PROCESS_TIMEOUT_SECONDS = 20;

    public static List<String> clearnetBitcoinNodes = Arrays.asList(
            "138.68.117.247",
            "62.178.187.80",
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
            "5quyxpxheyvzmb2d.onion:8000", // @mrosseel
            "ef5qnzx6znifo3df.onion:8000", // @alexej996
            "s67qglwhkgkyvr74.onion:8000", // @emzy
            "jhgcy2won7xnslrb.onion:8000" // @sqrrm
    );

    Set<String> errorNodes = new HashSet<>();

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
                handlePricenodeError(address, "", getFeesResult.getError());
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handlePricenodeError(address, getFeesResult.getResult(), "Result does not contain expected keyword");
                continue;
            }

            ProcessResult getVersionResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getVersion", PROCESS_TIMEOUT_SECONDS);
            if (getVersionResult.getError() != null) {
                handlePricenodeError(address, "", getVersionResult.getError());
                continue;
            }
            correct = PRICE_NODE_VERSION.equals(getVersionResult.getResult());
            if (!correct) {
                handlePricenodeError(address, getVersionResult.getResult(), "Incorrect version:" + getVersionResult.getResult());
                continue;
            }

            markAsGoodNode(address);
        }
    }

    public void checkSeedNodes() {

    }

    private void checkBitcoinNode(List<String> ipAddresses, boolean overTor) {
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            try {
                Process pr = rt.exec("pipenv run " + (overTor ? "torify " : "") + "python ./src/main/python/protocol.py " + address);
                boolean noTimeout = pr.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!noTimeout) {
                    handleBitcoinNodeError(result, "Timeout");
                    continue;
                }
                String resultString = convertStreamToString(pr.getInputStream());
                log.info(resultString.toString());
                String[] splitResult = resultString.split(",");
                if (splitResult.length != 4) {
                    handleBitcoinNodeError(result, "Incorrect result length:" + resultString);
                    continue;
                }
                result.setVersion(splitResult[1]);
                result.setHeight(Long.parseLong(splitResult[2]));
                result.setServices(Integer.parseInt(splitResult[3].trim()));
            } catch (IOException e) {
                handleBitcoinNodeError(result, e.getMessage());
                continue;
            } catch (InterruptedException e) {
                handleBitcoinNodeError(result, e.getMessage());
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

    public void handlePricenodeError(String address, String result, String reason) {
        log.error("Error in pricenode, reason: {}, result: {}", reason, result);
        if (!isAlreadyBadNode(address)) {
            SlackTool.send(api, "Price node: " + address, reason);
        }
    }

    public void handleBitcoinNodeError(BitcoinNodeResult result, String reason) {
        log.error("Error in Bitcoin node {}, reason: {}", result.getAddress(), reason);
        if (!isAlreadyBadNode(result.getAddress())) {
            SlackTool.send(api, "Bitcoin node: " + result.getAddress(), reason);
        }
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void markAsGoodNode(String address) {
        boolean removed = errorNodes.remove(address);
        if (removed) {
            SlackTool.send(api, address, "No longer in error");
        }
    }

    private boolean isAlreadyBadNode(String address) {
        return !errorNodes.add(address);
    }

    public static void main(String[] args) {
        Uptime uptime = new Uptime();
        log.info("Startup. All nodes in error will be shown fully in this first run.");
        SlackTool.send(api, "Monitoring Node startup", "Startup. All nodes in error will be shown fully in this first run.");
        while (true) {
            log.info("Starting checks...");
            uptime.checkPriceNodes(onionPriceNodes, true);
            uptime.checkClearnetBitcoinNodes(clearnetBitcoinNodes);
            uptime.checkOnionBitcoinNodes(onionBitcoinNodes);
            log.info("Stopping checks, now sleeping for {} seconds.", LOOP_SLEEP_SECONDS);

            try {
                Thread.sleep(1000 * LOOP_SLEEP_SECONDS);
            } catch (InterruptedException e) {
                log.error("Error during sleep", e);
            }
        }
    }
}
