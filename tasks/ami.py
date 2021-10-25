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
from scylla_arms.configparser import properties_parser, build_metadata_parser
import git

machine_image_repo = 'git@github.com:scylladb/scylla-machine-image.git'
machine_image_branch = 'master'
machine_image_checkout_dir = 'scylla-machine-image'

console = Console(color_system=None)
workspace = str(Path(__file__).parent.parent)

general_p = properties_parser('general.properties')
branch_p = properties_parser('branch-specific.properties')

build_metadata_file = general_p.get('buildMetadataFile')
ami_id_file = general_p.get('amiIdFile')
ami_default_test_region = general_p.get('amiDefaultTestRegion')

called_builds_dir = branch_p.get('calledBuildsDir')
unified_deb_job_name = branch_p.get('unifiedDebJobName')
centos_job_name = branch_p.get('centosJobName')
scylla_unified_pkg_repo = branch_p.get('scyllaUnifiedPkgRepo')
product_name = branch_p.get('productName')

@task
def build(c, job_name, build_num, artifact_url, distro, test_existing_ami_id, tag_test=True):
    print(f'Jenkins params:{c.persisted.dict()}')
    repo = Repo.clone_from(machine_image_repo, machine_image_branch, machine_image_checkout_dir)
    if distro != 'ubuntu:20.04' and distro != 'centos:7':
        raise Exception('Unsupported distro')
    if not test_existing_ami_id:
        if artifact_url != 'latest':
            r = requests.get(f'http://{artifact_url}/{build_metadata_file}')
            r.raise_for_status()
            metadata = r.text
        else:
            if not job_name:
                if distro == 'ubuntu:20.04':
                    job_name = f'{called_builds_dir}/job/{unified_deb_job_name}'
                    metadata_url_field_name = 'unified-deb-url'
                else:
                    job_name = f'{called_builds_dir}/job/{centos_job_name}'
                    metadata_url_field_name = 'centos-rpm-repo-url'
            if not build_num:
                build_num = 'lastSuccessfulBuild'
            with open('/var/tmp/takuya-api-token.txt') as f:
                token = f.read().strip()
            r = requests.get(f'https://jenkins.scylladb.com/view/master/job{job_name}/{build_num}/artifact/{build_metadata_file}', auth=('syuu1228',token))
            r.raise_for_status()
            metadata_txt = r.text
        with open(build_metadata_file, 'w') as f:
            f.write(metadata_txt)
        print(f'[{build_metadata_file}]\n{metadata_txt}\n')
        metadata = build_metadata_parser(build_metadata_file)
        scylla_release = metadata.get('scylla-release')
        scylla_version = metadata.get('scylla-version')
        repo_url = 'http://' + metadata.get(metadata_url_field_name)
        if distro == 'ubuntu:20.04':
            repo_url += scylla_unified_pkg_repo + '/scylla.list'
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
            c.run(f'{workspace}/tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- {script_name} --product {product_name} --repo {repo_url} --log-file {workspace}/ami.log', env=ami_env)
        with open('./ami.log') as f:
            ami_log = f.read()
        match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
        if not match:
            raise Exception('AMI build failed')
        ami_id = match.group(1)
    else:
        ami_id = test_existing_ami_id

    ami_id_p = properties_parser(ami_id_file)
    ami_id_p.set('scylla_ami_id', ami_id)
    ami_id_p.commit()
    metadata.set('scylla-ami-id', ami_id)
    metadata.set('ami-base-os', distro)
    metadata.commit()

    if not test_existing_ami_id and tag_test:
        ami_env = os.environ.copy()
        ami_env['DPACKAGER_TOOL'] = 'podman'
        ami_env['DOCKER_IMAGE'] = 'image_fedora-33'
        res = c.run(f'./tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- bash -c "aws ec2 --region {ami_default_test_region} describe-tags --filters Name=resource-id,Values={ami_id} Name=key,Values=ScyllaVersion > version_tag.json"', env=ami_env)
        with open('version_tag.json') as f:
            version_tag_json = json.loads(f.read())
        version_tag = version_tag_json['Tags'][0]['Value']
        if version_tag.startswith(f'{scylla_version}-{scylla_release}'):
            print(f'Success: AMI tag version: |{version_tag}|. Contains |{scylla_version}| and |{scylla_release}| as expected')
        else:
            raise Exception(f'AMI tag version: |{version_tag}|. Does not contain |{scylla_version}| and |{scylla_release}| as expected')


@task
def test(c):
    if not os.path.exists(ami_id_file):
        raise Exception(f'{ami_id_file} does not exist')

    ami_id_p = properties_parser(ami_id_file)
    ami_id = ami_id_p.get('scylla_ami_id')
    print('scylla_ami_id:{ami_id}')

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
