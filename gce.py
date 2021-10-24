import os
import sys
import re
import shutil
import argparse
import json
import subprocess

gce_test_service_accout = 'serviceAccount:skilled-adapter-452@appspot.gserviceaccount.com'

dpackager_cmd = '../../../tools/packaging/dpackager -e GOOGLE_APPLICATION_CREDENTIALS=$GOOGLE_APPLICATION_CREDENTIALS -v $GOOGLE_APPLICATION_CREDENTIALS:$GOOGLE_APPLICATION_CREDENTIALS'
dpackager_cwd = './scylla-machine-image/gce/image'

gce_env = os.environ.copy()
gce_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
gce_env['DPACKAGER_TOOL'] = 'podman'

def dpackager(cmd, capture_output=False, encoding=None):
    print(f'dpackager({cmd})')
    return subprocess.run(f'{dpackager_cmd} -- {cmd}', cwd=dpackager_cwd, shell=True, check=True, env=gce_env, capture_output=capture_output, encoding=encoding)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo')
    args = parser.parse_args()

    print('--- enviroment variables ---')
    print(os.environ)
    print('------')

    shutil.copyfile('./json_files/gce_variables.json', './scylla-machine-image/gce/image/variables.json')
    gce_env = os.environ.copy()
    gce_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
    gce_env['DPACKAGER_TOOL'] = 'podman'
    dpackager(f'./build_deb_image.sh --product scylla --repo {args.repo} --log-file build/gce-image.log')
    with open('./scylla-machine-image/gce/image/build/gce-image.log') as f:
        gce_log = f.read()
    match = re.search(r'A disk image was created: (.+)$', gce_log, flags=re.MULTILINE)
    if not match:
        print('GCE build failed')
        sys.exit(1)
    gce_image_name = match.group(1)
