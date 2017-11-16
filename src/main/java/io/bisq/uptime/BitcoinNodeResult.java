package io.bisq.uptime;

import lombok.Data;

/*


 */
@Data
public class BitcoinNodeResult {
    String address;
    String version;
    long height;
    int services;
}
