import requests
import jenkins
from scylla_arms.configparser import properties_parser, build_metadata_parser

general_p = properties_parser('general.properties')
build_metadata_file = general_p.get('buildMetadataFile')
server = 'https://jenkins.scylladb.com/'
user = 'syuu1228'
with open('/var/tmp/takuya-api-token.txt') as f:
    token = f.read().strip()

def _get_job_url(job_name):
    j = jenkins.Jenkins(server, username = user, password = token)
    build_url = j.build_job_url(job_name)
    return build_url.rstrip('build')

def get_artifact(artifact, job_name, build_num, artifact_url=None):
    if not artifact_url:
        job_url = _get_job_url(job_name)
        if not build_num:
            build_num = 'lastSuccessfulBuild'
        print(f'{job_url}/{build_num}/artifact/{artifact}')
        r = requests.get(f'{job_url}/{build_num}/artifact/{artifact}', auth=(user,token))
    else:
        print(f'http://{artifact_url}/{artifact}')
        r = requests.get(f'http://{artifact_url}/{artifact}')
    r.raise_for_status()
    with open(artifact, 'w') as f:
        f.write(r.text)

def fetch_metadata_value(job_name, build_num, field_name, artifact_url=None):
    get_artifact(build_metadata_file, job_name, build_num, artifact_url)
    metadata = build_metadata_parser(build_metadata_file)
    return metadata.get(field_name)
