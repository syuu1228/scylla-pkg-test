import os
import shutil
import re
from scylla_arms.configparser import properties_parser

branch_p = properties_parser('branch-specific.properties')
ami_id_file = general_p.get('amiIdFile')

def build_ami(c, repo_url, distro, product_name):
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
        c.run(f'../../../tools/packaging/dpackager -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -- {script_name} --product {product_name} --repo http://{repo_url} --log-file ../../../ami.log', env=ami_env)
    with open('./ami.log') as f:
        ami_log = f.read()
    match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
    if not match:
        raise Exception('AMI build failed')
    ami_id = match.group(1)
    ami_id_p = properties_parser(ami_id_file)
    ami_id_p.set('scylla_ami_id', ami_id)
    ami_id_p.commit()
