#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: package_release.sh <version>}"
package="aNMJ-morph-plus-${version}"
dist_dir="${2:-dist}"
package_dir="${dist_dir}/${package}"

rm -rf "${package_dir}"
mkdir -p "${package_dir}"

cp LICENSE CITATION.cff "Reference Spreadsheet.xlsx" "${package_dir}/"
cp -R "Reference Images" "${package_dir}/Reference Images"

plugin_jar="$(find target -maxdepth 1 -type f -name 'anmj-morph-plus-*.jar' ! -name '*-sources.jar' ! -name '*-tests.jar' -print -quit)"
if [[ -z "${plugin_jar}" ]]; then
  echo "Java plugin JAR not found; run 'mvn package' first" >&2
  exit 1
fi
cp "${plugin_jar}" "${package_dir}/aNMJ-morph-plus.jar"

if [[ -f README.md ]]; then
  cp README.md "${package_dir}/"
fi

mkdir -p "${dist_dir}"
(
  cd "${dist_dir}"
  rm -f "${package}.zip" "${package}.zip.sha256"
  zip -qr "${package}.zip" "${package}"
  sha256sum "${package}.zip" > "${package}.zip.sha256"
)

echo "Created ${dist_dir}/${package}.zip"
echo "Created ${dist_dir}/${package}.zip.sha256"
