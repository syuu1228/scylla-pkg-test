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

    with open('./json_files/gce_variables.json') as f:
        gce_vars = json.load(f)
    project_id = gce_vars['project_id']
    print('project_id:{project_id}')

    dpackager('gcloud auth activate-service-account --key-file $GOOGLE_APPLICATION_CREDENTIALS')
    dpackager(f'gcloud config set project {project_id}')
    try:
        image_info = dpackager(f'gcloud compute images describe {gce_image_name}', capture_output=True, encoding='utf-8').stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f'returncode:{e.returncode}')
        print(f'stdout:{e.stdout}')
        print(f'stderr:{e.stderr}')
        raise
    match = re.search(r'^id: \'(.+)\'$', image_info, flags=re.MULTILINE)
    if not match:
        print('Not able to find image id')
        sys.exit(1)
    gce_image_id = match.group(1)
    dpackager(f'gcloud compute images add-iam-policy-binding {gce_image_id} --member="{gce_test_service_account}" --role="roles/compute.imageUser" --project {project_id}"')
