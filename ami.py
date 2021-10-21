import os
import sys
import re
import shutil
import argparse
from subprocess import run

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo')
    args = parser.parse_args()

    print('--- enviroment variables ---')
    print(os.environ)
    print('------')

    shutil.copyfile('./json_files/ami_variables.json', './scylla-machine-image/aws/ami/variables.json')
    ami_env = os.environ.copy()
    ami_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
    ami_env['DPACKAGER_TOOL'] = 'podman'
    run(f'bash -x ../../../tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- ./build_deb_ami.sh --product scylla --repo {args.repo} --log-file ami.log', cwd='./scylla-machine-image/aws/ami', shell=True, check=True, env=ami_env)
    with open('./scylla-machine-image/aws/ami/build/ami.log') as f:
        ami_log = f.read()
    match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
    if not match:
        print('AMI build failed')
        sys.exit(1)

    scylla_ami_id = match.group(1)
    with open('./amiId.properties', 'w') as f:
        f.write(f'scylla_ami_id={scylla_ami_id}')
