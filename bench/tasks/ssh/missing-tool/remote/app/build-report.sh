#!/bin/sh
# Summarises data/sales.csv (region,units,unit_price) into out/summary.json:
#   {"regions": {"<region>": {"units": <total units>, "revenue": <units x unit_price, 2 decimals>}}, "total_revenue": <sum>}
# Uses csvsum from our old ops-tools package.
set -eu
cd "$(dirname "$0")"
csvsum --group region --sum units --product units,unit_price data/sales.csv > out/summary.json
echo "wrote out/summary.json"
