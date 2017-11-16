package io.bisq.uptime;

import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackMessage;

/**
 * Created by mike on 28/02/16.
 */
public class SlackTool {

    public static void send(SlackApi api, String username, String body) {
        api.call(new SlackMessage(username, body));
    }
}
