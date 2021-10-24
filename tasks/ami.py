# pylint: disable=W,C,R
import os
import sys
import re
import shutil
from invoke import task, Collection
from rich import print  # pylint: disable=redefined-builtin
from rich.console import Console
import requests

console = Console(color_system=None)

def check_distro(distro):
    if distro != 'ubuntu:20.04' and distro != 'centos:7':
        raise Exception('Unsupported distro')

@task()
def fetch_repo_url(c, job_name, build_num, artifact_url, distro):
    check_distro(distro)
    if artifact_url == 'latest':
        if not job_name:
            if distro == 'ubuntu:20.04':
                job_name = '/scylla-master/job/unified-deb'
            elif distro == 'centos:7':
                job_name = '/scylla-master/job/centos-rpm'
        if not build_num:
            build_num = 'lastSuccessfulBuild'
        with open('/var/tmp/takuya-api-token.txt') as f:
            token = f.read().strip()
        r = requests.get(f'https://jenkins.scylladb.com/view/master/job{job_name}/{build_num}/artifact/00-Build.txt', auth=('syuu1228',token))
        metadata = r.text
        print(metadata)
        if distro == 'ubuntu:20.04':
            pattern = r'^unified-deb-url: (.+)$'
        elif distro == 'centos:7':
            pattern = r'^centos-rpm-url: (.+)$'
        match = re.search(pattern, metadata, flags=re.MULTILINE)
        if not match:
            raise Exception('repository URL not found')
        artifact_url = match.group(1)
        print(artifact_url)
    if distro == 'ubuntu:20.04':
        repo_url = f'http://{artifact_url}scylladb-master/scylla.list'
    else:
        repo_url = f'http://{artifact_url}scylla.repo'
    print(repo_url)
    with open('repo_url.txt', 'w') as f:
        f.write(repo_url)

@task()
def build(c, repo, distro, ami_id):
    check_distro(distro)
    if not ami_id:
        shutil.copyfile('./json_files/ami_variables.json', './scylla-machine-image/aws/ami/variables.json')
        ami_env = os.environ.copy()
        ami_env['DPACKAGER_TOOL'] = 'podman'
        if distro == 'ubuntu:20.04':
            ami_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
            script_name = './build_deb_ami.sh'
        elif distro == 'centos:7':
            ami_env['DOCKER_IMAGE'] = 'image_fedora-33'
            script_name = './build_ami.sh'
        with c.cd('./scylla-machine-image/aws/ami'):
            c.run(f'../../../tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- {script_name} --product scylla --repo {repo} --log-file build/ami.log', env=ami_env)
        with open('./scylla-machine-image/aws/ami/build/ami.log') as f:
            ami_log = f.read()
        match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
        if not match:
            raise Exception('AMI build failed')
        ami_id = match.group(1)
    with open('./amiId.properties', 'w') as f:
        f.write(f'scylla_ami_id={ami_id}')

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

ns = Collection(fetch_repo_url, build, test)
