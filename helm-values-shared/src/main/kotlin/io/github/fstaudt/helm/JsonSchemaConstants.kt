package io.github.fstaudt.helm

const val SCHEMA_VERSION = "https://json-schema.org/draft/2020-12/schema"

const val GENERATOR_LABEL = "helm-values 0.6.1"

const val NEW_LINE = "\n\\n"

const val HELM_SCHEMA_FILE = "values.schema.json"
const val VALUES_SCHEMA_FILE = "values.schema.json"
const val PATCH_VALUES_SCHEMA_FILE = "values.schema.patch.json"
const val GLOBAL_VALUES_DEPRECATION = """"
Schema for global values is deprecated and will be removed in version 1.0.0.
It is only kept in download and aggregation for retro-compatibility with schemas generated by version 0.3.0.
Global values should instead be integrated in properties/global of values.schema.json.
"""

@Deprecated(GLOBAL_VALUES_DEPRECATION)
const val GLOBAL_VALUES_SCHEMA_FILE = "global-values.schema.json"
const val AGGREGATED_SCHEMA_FILE = "aggregated-values.schema.json"
const val PATCH_AGGREGATED_SCHEMA_FILE = "aggregated-values.schema.patch.json"
const val EXTRA_VALUES_SCHEMA_FILE = "extra-values.schema.json"
const val PATCH_EXTRA_VALUES_SCHEMA_FILE = "extra-values.schema.patch.json"
