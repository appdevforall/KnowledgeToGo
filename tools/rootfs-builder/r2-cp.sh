#!/usr/bin/env bash
# =============================================================================
# r2-cp.sh : K2GO-511. `s3cp` = `aws s3 cp` to R2 with retries. Sourced by the bake workflow.
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# R2 drops a multipart upload now and then (InvalidPart, first-byte timeout), which failed a
# finished 70-min bake. Retry the whole cp; each attempt restarts the upload.
# Needs ENDPOINT (the R2 S3 endpoint) and the AWS_* credentials in the environment.
# =============================================================================
s3cp() {
    local n
    for n in 1 2 3 4 5; do
        aws s3 cp "$@" --endpoint-url "$ENDPOINT" && return 0
        echo "!! aws s3 cp failed (attempt $n/5), retry in $((n * 15))s" >&2
        sleep $((n * 15))
    done
    return 1
}
