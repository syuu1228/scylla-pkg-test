from invoke import Collection

import tasks.ami
import tasks.scylla
from libs.configparser import properties_parser, build_metadata_parser

ns = Collection(tasks.ami, tasks.scylla)
