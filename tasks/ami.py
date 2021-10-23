# pylint: disable=W,C,R
import os
import sys
import re
import shutil
from invoke import task, Collection
from rich import print  # pylint: disable=redefined-builtin
from rich.console import Console

console = Console(color_system=None)

@task()
def build(c, repo):
    shutil.copyfile('./json_files/ami_variables.json', './scylla-machine-image/aws/ami/variables.json')
    ami_env = os.environ.copy()
    ami_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
    ami_env['DPACKAGER_TOOL'] = 'podman'
    with c.cd('./scylla-machine-image/aws/ami'):
        c.run(f'../../../tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- ./build_deb_ami.sh --product scylla --repo {repo} --log-file build/ami.log', env=ami_env)
    with open('./scylla-machine-image/aws/ami/build/ami.log') as f:
        ami_log = f.read()
    match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
    if not match:
        print('AMI build failed')
        sys.exit(1)
    scylla_ami_id = match.group(1)
    with open('./amiId.properties', 'w') as f:
        f.write(f'scylla_ami_id={scylla_ami_id}')

@task
def test(c, ami_id):
    sct_env = os.environ.copy()
    sct_env['SCT_COLLECT_LOGS'] = 'false'
    sct_env['SCT_CONFIG_FILES'] = 'test-cases/artifacts/ami.yaml'
    sct_env['SCT_AMI_ID_DB_SCYLLA'] = ami_id
    sct_env['SCT_REGION_NAME'] = 'us-east-1'
    sct_env['SCT_INSTANCE_TYPE_DB'] = 'i3.2xlarge'
    sct_env['SCT_POST_BEHAVIOR_DB_NODES'] = 'destroy'
    sct_env['SCT_IP_SSH_CONNECTIONS'] = 'private'
    sct_env['SCT_INSTANCE_PROVISION'] = 'spot'
    with c.cd('./scylla-cluster-tests'):
        c.run('./docker/env/hydra.sh run-test artifacts_test --backend aws', env=sct_env, pty=True)

@task(build, test)
def all_tasks(ctx):
    pass

ns = Collection(all_tasks, build, test)
