#!/bin/sh
# Report by email failing connections to bisq seed nodes.
#
# Exit with status 2 if unreachable nodes were detected.
#
# You may drop this under ``/etc/cron.hourly``.
# Please make sure that your system can send emails.
#
# Requirements: coreutils, tor, netcat, mail.
#
# Author: Ivan Vilata-i-Balaguer <ivan@selidor.net>

sn=$1
failing_seed_nodes=''

torify nc -z $(echo "$sn" | tr ':' ' ') > /dev/null 2>&1
if [ $? != 0 ]; then
    failing_seed_nodes="$failing_seed_nodes $sn"
    exit 1
fi
#echo $failing_seed_nodes
exit 0

