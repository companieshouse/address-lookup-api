#!/bin/bash

PORT=8080

exec java -jar -Dserver.port="${PORT}" -XX:MaxRAMPercentage=80 "address-lookup-api.jar"