#!/usr/bin/env python3

import os
import sys
import re
import shutil
import argparse
from subprocess import run

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ami-id', required=True)
    args = parser.parse_args()

    sct_env = os.environ.copy()
    sct_env['SCT_COLLECT_LOGS'] = 'false'
    sct_env['SCT_CONFIG_FILES'] = 'test-cases/artifacts/ami.yaml'
    sct_env['SCT_AMI_ID_DB_SCYLLA'] = args.ami_id
    sct_env['SCT_REGION_NAME'] = 'us-east-1'
    sct_env['SCT_INSTANCE_TYPE_DB'] = 'i3.2xlarge'
    sct_env['SCT_POST_BEHAVIOR_DB_NODES'] = 'destroy'
    sct_env['SCT_IP_SSH_CONNECTIONS'] = 'private'
    sct_env['SCT_INSTANCE_PROVISION'] = 'spot'
    run('./docker/env/hydra.sh run-test artifacts_test --backend aws', cwd='./scylla-cluster-tests', shell=True, check=True, env=sct_env)
