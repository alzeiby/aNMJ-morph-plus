#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: package_release.sh <version>}"
package="aNMJ-morph-plus-${version}"
dist_dir="${2:-dist}"
package_dir="${dist_dir}/${package}"

rm -rf "${package_dir}"
mkdir -p "${package_dir}"

cp "aNMJ-morph macro.txt" LICENSE "Reference Spreadsheet.xlsx" "${package_dir}/"
cp -R "Reference Images" "${package_dir}/Reference Images"

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
