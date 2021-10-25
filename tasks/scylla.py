# pylint: disable=W,C,R
import os
import sys
import re
import shutil
from invoke import task, Collection
import requests

@task()
def build(c):
    with c.cd('./scylla'):
        c.run(f'./tools/toolchain/dbuild  ./configure.py')
        c.run(f'./tools/toolchain/dbuild ninja')

ns = Collection(build)
