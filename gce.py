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

    shutil.copyfile('./json_files/gce_variables.json', './scylla-machine-image/gce/image/variables.json')
    ami_env = os.environ.copy()
    ami_env['DOCKER_IMAGE'] = 'image_ubuntu20.04'
    ami_env['DPACKAGER_TOOL'] = 'podman'
    run(f'../../../tools/packaging/dpackager -- ./build_deb_image.sh --product scylla --repo {args.repo} --log-file build/gce-image.log', cwd='./scylla-machine-image/gce/image', shell=True, check=True, env=ami_env)
    with open('./scylla-machine-image/gce/image/build/gce-image.log') as f:
        ami_log = f.read()
    match = re.search(r'A disk image was created: (.+)$', ami_log, flags=re.MULTILINE)
    if not match:
        print('GCE build failed')
        sys.exit(1)

    scylla_gce_id = match.group(1)
    print(scylla_gce_id)
