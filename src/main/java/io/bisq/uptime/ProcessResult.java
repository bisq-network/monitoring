package io.bisq.uptime;

import lombok.AllArgsConstructor;
import lombok.Data;

/*

 */
@Data
@AllArgsConstructor
public class ProcessResult {
    String result;
    String error;
}
