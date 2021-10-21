import os
import sys
import re
import shutil
from subprocess import run

repo = 'http://downloads.scylladb.com/unstable/scylla/master/deb/unified/2021-08-26T12:02:36Z/scylladb-master/scylla.list'

shutil.copyfile('./json_files/ami_variables.json', './scylla-machine-image/aws/ami/variables.json')
os.chdir('./scylla-machine-image')
run(f'../tools/packaging/dpackager ./packer/build_deb_image.sh --product scylla --repo {repo} --log-file ami.log', shell=True, check=True)
with open('./aws/ami/build/ami.log') as f:
    ami_log = f.read()
match = re.search(r'^us-east-1: (.+)$', ami_log, flags=re.MULTILINE)
if not match:
    print('AMI build failed')
    sys.exit(1)

scylla_ami_id = match.group(1)
print(f'scylla_ami_id={scylla_ami_id}')
