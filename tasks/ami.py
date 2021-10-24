# pylint: disable=W,C,R
import os
import sys
import re
import shutil
from invoke import task, Collection
from rich import print  # pylint: disable=redefined-builtin
from rich.console import Console
import requests
import json
from pathlib import Path

console = Console(color_system=None)
workspace = str(Path(__file__).parent.parent)

def get_value_from_metadata(metadata, tag):
    match = re.search(rf'^{tag}: (.+)$', metadata, re.MULTILINE)
    if not match:
        raise Exception(f'{tag} not found')
    return match.group(1)

@task
def build(c, job_name, build_num, artifact_url, distro, test_existing_ami_id, tag_test=True):
    if distro != 'ubuntu:20.04' and distro != 'centos:7':
        raise Exception('Unsupported distro')
    if not test_existing_ami_id:
        if artifact_url != 'latest':
            r = requests.get(f'http://{artifact_url}/00-Build.txt')
            r.raise_for_status()
            metadata = r.text
        else:
            if not job_name:
                if distro == 'ubuntu:20.04':
                    job_name = '/scylla-master/job/unified-deb'
                else:
                    job_name = '/scylla-master/job/centos-rpm'
            if not build_num:
                build_num = 'lastSuccessfulBuild'
            with open('/var/tmp/takuya-api-token.txt') as f:
                token = f.read().strip()
            r = requests.get(f'https://jenkins.scylladb.com/view/master/job{job_name}/{build_num}/artifact/00-Build.txt', auth=('syuu1228',token))
            r.raise_for_status()
            metadata = r.text
        with open('./00-Build.txt', 'w') as f:
            f.write(metadata)
        print('[00-Build.txt]\n{}\n'.format(metadata))
        scylla_release = get_value_from_metadata(metadata, 'scylla-release')
        scylla_version = get_value_from_metadata(metadata, 'scylla-version')
        if distro == 'ubuntu:20.04':
            artifact_url = get_value_from_metadata(metadata, 'unified-deb-url')
        else:
            artifact_url = get_value_from_metadata(metadata, 'centos-rpm-url')
        if distro == 'ubuntu:20.04':
            repo_url = f'http://{artifact_url}scylladb-master/scylla.list'
        else:
            repo_url = f'http://{artifact_url}scylla.repo'
        print(f'repo_url:{repo_url}')

        shutil.copyfile('./json_files/ami_variables.json', './scylla-machine-image/aws/ami/variables.json')
        ami_env = os.environ.copy()
        ami_env['DPACKAGER_TOOL'] = 'podman'
        if distro == 'ubuntu:20.04':
            ami_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
            script_name = './build_deb_ami.sh'
        else:
            ami_env['DOCKER_IMAGE'] = 'image_fedora-33'
            script_name = './build_ami.sh'
        with c.cd('./scylla-machine-image/aws/ami'):
            c.run(f'{workspace}/tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- {script_name} --product scylla --repo {repo_url} --log-file {workspace}/ami.log', env=ami_env)
        with open('./ami.log') as f:
            ami_log = f.read()
        match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
        if not match:
            raise Exception('AMI build failed')
        ami_id = match.group(1)
    else:
        ami_id = test_existing_ami_id

    with open('./amiId.properties', 'w') as f:
        f.write(f'scylla_ami_id={ami_id}\n')
    with open('./00-Build.txt', 'a') as f:
        f.write(f'scylla-ami-id: {ami_id}\n')
        f.write(f'ami-base-os: {distro}\n')

    if not test_existing_ami_id and tag_test:
        ami_env = os.environ.copy()
        ami_env['DPACKAGER_TOOL'] = 'podman'
        ami_env['DOCKER_IMAGE'] = 'image_fedora-33'
        res = c.run(f'./tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- bash -c "aws ec2 --region us-east-1 describe-tags --filters Name=resource-id,Values={ami_id} Name=key,Values=ScyllaVersion > version_tag.json"', env=ami_env)
        with open('version_tag.json') as f:
            version_tag_json = json.loads(f.read())
        version_tag = version_tag_json['Tags'][0]['Value']
        if version_tag.startswith(f'{scylla_version}-{scylla_release}'):
            print(f'Success: AMI tag version: |{version_tag}|. Contains |{scylla_version}| and |{scylla_release}| as expected')
        else:
            raise Exception(f'AMI tag version: |{version_tag}|. Does not contain |{scylla_version}| and |{scylla_release}| as expected')


@task
def test(c):
    with open('./amiId.properties') as f:
        properties = f.read()
    print(properties)
    match = re.search(r'^scylla_ami_id=(.+)$', properties, flags=re.MULTILINE)
    if not match:
        raise Exception("Missing AMI ID. Expected property scylla_ami_id on file amiPropertiesFile created on build phase. Can't run tests")
    ami_id = match.group(1)
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

ns = Collection(build, test)
