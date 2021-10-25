#!/usr/bin/env bash

echo $AWS_SECRET_ACCESS_KEY
pip3 install virtualenv
python3 -m virtualenv poc-venv
source poc-venv/bin/activate && pip3 install invoke pydantic requests
poc-venv/bin/invoke "$@"
