#!/bin/bash
rm -f OutputFile
pkill sender
pkill receiver
./waitForPorts
./receiver & sleep 1
