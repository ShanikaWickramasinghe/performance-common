#!/bin/bash -e
# Copyright (c) 2018, WSO2 Inc. (http://wso2.org) All Rights Reserved.
#
# WSO2 Inc. licenses this file to you under the Apache License,
# Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# ----------------------------------------------------------------------------
# Setup script
# ----------------------------------------------------------------------------

# Make sure the script is running as root.
if [ "$UID" -ne "0" ]; then
    echo "You must be root to run $0. Try following"
    echo "sudo ${script_name:-$0}"
    exit 9
fi

script_dir=$(dirname "$0")
dist_upgrade=false
declare -a packages
declare -a download_urls
declare -a download_output_names

function usage() {
    echo ""
    echo "Usage: "
    echo -n "${script_name:-$0}"
    if declare -F usageCommand >/dev/null 2>&1; then
        echo -n " $(usageCommand)"
    fi
    echo "  [-g] [-p <package>] [-w <url_to_download>] [-o <output_name>]"
    echo ""
    echo "-g: Upgrade distribution"
    echo "-p: Package to install. You can give multiple -p options."
    echo "-w: Download URLs. You can give multiple URLs to download."
    echo "-o: Output name of the downloaded file. You can give multiple names for a given set of URLs."
    if declare -F usageHelp >/dev/null 2>&1; then
        echo "$(usageHelp)"
    fi
    echo "-h: Display this help and exit."
    echo ""
}

# Other script depending on this common script must have these options.
while getopts "gp:w:o:h" opts; do
    case $opts in
    g)
        dist_upgrade=true
        ;;
    p)
        packages+=("${OPTARG}")
        ;;
    w)
        download_urls+=("${OPTARG}")
        ;;
    o)
        download_output_names+=("${OPTARG}")
        ;;
    h)
        usage
        exit 0
        ;;
    *)
        usage
        exit 1
        ;;
    esac
done

if [ ! "${#download_urls[@]}" -eq "${#download_output_names[@]}" ]; then
    echo "Number of download URLs should be equal to number of output names."
    exit 1
fi

if declare -F validate >/dev/null 2>&1; then
    validate
fi

# Update packages
echo "Updating packages"
apt-get update

echo -ne "\n"

# Upgrade distribution
if [ "$dist_upgrade" = true ]; then
    echo "Upgrading the distribution"
    apt-get -y dist-upgrade
    apt-get -y autoremove
    apt-get -y autoclean
fi

# Install OS Packages
for p in ${packages[*]}; do
    echo "Installing $p package"
    apt-get install -y $p
    echo -ne "\n"
done

# Download files
for ix in ${!download_urls[*]}; do
    echo "Downloading ${download_urls[$ix]} and saving in ${download_output_names[$ix]}"
    wget -q "${download_urls[$ix]}" -O "${download_output_names[$ix]}"
    echo -ne "\n"
done

# Install SAR
echo "Installing and configuring System Activity Report"
$script_dir/../sar/install-sar.sh

echo -ne "\n"

FUNC=$(declare -f setup)
if [[ ! -z $FUNC ]]; then
    bash -c "$FUNC; setup"
fi