import json
import os
import re
from typing import Dict, Any

from pydantic import BaseSettings


def jenkins_params_settings_source(settings: BaseSettings) -> Dict[str, Any]:
    """
    A simple settings source that loads variables from a JSON file
    at the project's root.

    Here we happen to choose to use the `env_file_encoding` from Config
    when reading `config.json`
    """
    if raw_jenkins_params := os.getenv("JENKINS_PARAMS"):
        raw_jenkins_params = raw_jenkins_params[1:-1]
        parsed_params = {}
        scalar_params_patt = re.compile(r"(\w+):([^,]*)")
        for k, v in scalar_params_patt.findall(raw_jenkins_params):
            parsed_params[k] = v.strip()
        list_params_patt = re.compile(r"(\w+):(\[[^]]+\])")
        for k, v in list_params_patt.findall(raw_jenkins_params):
            parsed_params[k] = json.loads(v)
        return parsed_params
    else:
        return {}


class Settings(BaseSettings):

    class Config:
        env_file_encoding = 'utf-8'
        extra = "ignore"

        @classmethod
        def customise_sources(
            cls,
            init_settings,
            env_settings,
            file_secret_settings,
        ):
            return (
                init_settings,
                jenkins_params_settings_source,
                env_settings,
                file_secret_settings,
            )
